(ns com.repldriven.queenswood.modulr-simulator.scheme
  "What happens to a payment once Modulr has accepted it. The test values
  every payment simulator shares decide an outcome before any money
  moves: a creditor sort code `000000` is declined, and the creditor
  name `6a41a29eafcf455493` held for compliance then declined. Otherwise
  a payment the source account cannot cover waits `PENDING_FOR_FUNDS`
  until money arrives or `pending-for-funds-ms` passes, when it expires
  without a notification, as Modulr's do, and a later payment from the
  account waits behind it. A payment it can cover is processed when it
  is accepted: the source is debited and, where the destination is an
  account the simulator holds, credited in the same step, so payments
  move money in the order they were accepted on every account they
  touch. Telling each customer, and sending a payment on where another
  simulator on the scheme holds the destination, follow in the
  background.
  A payment to an account the simulator has closed, or one the
  simulator holding it refuses, is returned to its source, as the
  beneficiary's bank would, and a control route returns any other
  processed payment the same way."
  (:require
    [com.repldriven.queenswood.modulr-simulator.deliveries :as deliveries]
    [com.repldriven.queenswood.modulr-simulator.ledger :as ledger]

    [com.repldriven.queenswood.scheme-simulator.interface :as
     scheme-simulator]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(def held-name "6a41a29eafcf455493")

(def declined-sort-code "000000")

(def ^:private default-pending-for-funds-ms 30000)

(defn- pause
  [{:keys [webhook-delay-ms]}]
  (when (pos? (or webhook-delay-ms 0)) (Thread/sleep (long webhook-delay-ms))))

(defn- party
  [account name]
  {:Name (or name (:external-reference account))
   :Identifier {:Type "SCAN"
                :SortCode (:sort-code account)
                :AccountNumber (:account-number account)}})

(def ^:private scheme-id-prefix "PAYPORT:")

(defn- scheme-id
  "The scheme's own id for a payment, as a PAYOUT's `SchemeInfo` and a
  return's `OriginalSchemeId` carry it."
  [p]
  (str "MODULO00" (:id p)))

(defn- payout
  [p status]
  (let [{:keys [id details externalReference transaction-id]} p
        {:keys [sourceAccountId amount reference destination]} details]
    {:EventName "PAYOUT"
     :EventId (str (utility/uuidv7))
     :EventTime (ledger/timestamp)
     :Status status
     :PaymentId id
     :TransactionId transaction-id
     :TransactionType (if (= "ACCOUNT" (:type destination))
                        "INT_INTERC"
                        "PO_FAST")
     :AccountId sourceAccountId
     :Amount (ledger/amount-str (ledger/amount amount))
     :DateTime (ledger/timestamp)
     :Reference reference
     :ExternalReference externalReference
     :SchemeInfo {:Id (scheme-id p) :ResponseCode "0000"}}))

(defn payin
  [{:keys [payment-id transaction-id type account payer amount currency
           reference source-external-reference]}]
  {:EventName "PAYIN"
   :EventId (str (utility/uuidv7))
   :EventTime (ledger/timestamp)
   :Type type
   :PaymentId payment-id
   :TransactionId transaction-id
   :AccountId (:id account)
   :Amount (ledger/amount-str (ledger/amount amount))
   :Currency (or currency (:currency account))
   :DateTime (ledger/timestamp)
   :PaymentAppliedTime (ledger/timestamp)
   :PayerName (:Name payer)
   :Payer payer
   :Payee (party account nil)
   :PaymentReference reference
   :SourceExternalReference source-external-reference
   :SchemeInfo {:Id (str "FP" payment-id)}})

(defn compliance
  [account payment-id status]
  {:EventName "PAYMENTCOMPLIANCESTATUS"
   :EventId (str (utility/uuidv7))
   :EventTime (ledger/timestamp)
   :AccountBid (:id account)
   :PaymentBid payment-id
   :CustomerBid (:customer-id account)
   :ComplianceStatus status})

(defn- finish
  [state config p status]
  (let [p
        (ledger/update-payment state (:id p) (fn [p] (assoc p :status status)))
        source (ledger/account state (get-in p [:details :sourceAccountId]))]
    (deliveries/notify state (:customer-id source) "PAYOUT" (payout p status))
    (pause config)
    p))

(defn- destination-account
  [state {:keys [type id sortCode accountNumber]}]
  (if (= "ACCOUNT" type)
    (ledger/account state id)
    (ledger/account-by-scan state sortCode accountNumber)))

(declare release-pending return-payment)

(defn- refusal
  "Modulr's return reason where the member holding the destination
  refused the payment: unknown where it holds no such account."
  [res]
  (cond
   (or (error/anomaly? res) (= 404 (:status res)))
   "BENSCANUNKNOWN"

   (= "failed" (get-in res [:body :admission-status]))
   "BENACCCLOSED"))

(defn- send-on
  "Send a payment to an account the simulator does not hold to the
  member of the scheme holding it, and return it where that member
  refuses it. Nothing where no member holds it."
  [state config p source]
  (let [{:keys [destination amount currency reference]} (:details p)
        {:keys [sortCode accountNumber]} destination
        res (when (and sortCode accountNumber)
              (scheme-simulator/send-inbound
               (:payment-scheme config)
               {:bban (str sortCode accountNumber)
                :amount amount
                :currency currency
                :reference reference
                :debtor-name (:Name (party source nil))}))]
    (when-let [reason (some-> res
                              refusal)]
      (pause config)
      (return-payment state config (:id p) reason))))

(defn- in-background
  [f]
  (future (try (f)
               (catch Exception e
                 (log/error e "Modulr simulator payment processing threw")))))

(defn- arrive
  "Tell the destination the payment reached it, where the simulator
  credited one; return the payment where the simulator has closed it; or
  send it on where it does not hold it."
  [state config p]
  (let [{:keys [details externalReference arrived-at]} p
        {:keys [destination amount currency reference sourceAccountId]} details
        target (destination-account state destination)
        source (ledger/account state sourceAccountId)]
    (cond
     arrived-at
     (deliveries/notify
      state
      (:customer-id target)
      "PAYIN"
      (payin {:payment-id (str (:id p) "-IN")
              :transaction-id (str "T" (:id p))
              :type
              (if (= "ACCOUNT" (:type destination)) "INT_INTERC" "PI_FAST")
              :account target
              :payer (party source nil)
              :amount amount
              :currency currency
              :reference reference
              :source-external-reference externalReference}))

     (nil? target)
     (send-on state config p source)

     :else
     (do (pause config) (return-payment state config (:id p) "BENACCCLOSED")))))

(def ^:private unsettled #{"SUBMITTED" "PENDING_FOR_FUNDS"})

(defn- move-money
  "Debit the source and credit the destination, where the simulator holds
  it open, marking the payment processed; or queue the payment behind the
  source's payments waiting for funds and return `:short` where one is
  ahead of it or the balance cannot cover it. Nil for a payment no longer
  unsettled. Runs under the state's lock."
  [state p]
  (let [{:keys [id]} p
        {:keys [status details]} (ledger/payment state id)
        {:keys [sourceAccountId amount destination]} details
        queue (ledger/waiting state sourceAccountId)]
    (cond
     (not (contains? unsettled status))
     nil

     (and (or (empty? queue) (= id (first queue)))
          (ledger/debit state sourceAccountId amount))
     (let [target (destination-account state destination)
           credited (when (and target (not= "CLOSED" (:status target)))
                      (ledger/credit state (:id target) amount)
                      (:id target))]
       (ledger/stop-waiting state sourceAccountId id)
       (ledger/update-payment state
                              id
                              (fn [p]
                                (cond-> (assoc p
                                               :status "PROCESSED"
                                               :transaction-id (str "T" id)
                                               :schemeId (str scheme-id-prefix
                                                              (scheme-id p)))
                                        credited
                                        (assoc :arrived-at credited)))))

     :else
     (do (when-not (some #{id} queue)
           (ledger/wait-for-funds state sourceAccountId id))
         :short))))

(defn- settle
  "Move the payment's money now, so payments move money in the order they
  were accepted, releasing what the destination was waiting for; then
  tell the customers and send it on in the background. `:processed`,
  `:short`, or nil for a payment no longer unsettled."
  [state config p]
  (let [taken (locking state (move-money state p))]
    (if (map? taken)
      (do (when-let [target (:arrived-at taken)]
            (release-pending state config target))
          (in-background (fn []
                           (pause config)
                           (finish state config taken "PROCESSED")
                           (arrive state config taken)))
          :processed)
      taken)))

(defn- expire
  [state config payment-id]
  (Thread/sleep (long (or (:pending-for-funds-ms config)
                          default-pending-for-funds-ms)))
  (let [p (ledger/payment state payment-id)
        account-id (get-in p [:details :sourceAccountId])
        expired (locking state
                  (when (= "PENDING_FOR_FUNDS"
                           (:status (ledger/payment state payment-id)))
                    (ledger/stop-waiting state account-id payment-id)
                    (ledger/update-payment state
                                           payment-id
                                           (fn [p]
                                             (assoc p :status "ER_EXPIRED")))))]
    (when expired
      (log/info "Modulr simulator payment expired waiting for funds"
                {:payment-id payment-id})
      (release-pending state config account-id))))

(defn release-pending
  "Settle, in the order they arrived, the payments waiting for funds on
  `account-id`, stopping at the first its balance cannot cover."
  [state config account-id]
  (loop []
    (when-let [id (first (ledger/waiting state account-id))]
      (case (settle state config (ledger/payment state id))
        :processed (recur)
        :short nil
        (do (ledger/stop-waiting state account-id id) (recur))))))

(defn- take-back
  "Mark a processed payout returned and move its money back to the
  source, taking it from the destination where the simulator credited
  one, as one step. The payment, or `{:refused message}`."
  [state payment-id]
  (locking state
    (let [{:keys [status type returned arrived-at details] :as p}
          (ledger/payment state payment-id)
          {:keys [sourceAccountId amount]} details]
      (cond
       (not (and (= "PAYOUT" type) (= "PROCESSED" status)))
       {:refused "Only a processed payment can be returned"}

       returned
       {:refused "The payment has already been returned"}

       (and arrived-at (not (ledger/debit state arrived-at amount)))
       {:refused "The destination no longer holds the payment's amount"}

       :else
       (do (ledger/credit state sourceAccountId amount)
           (ledger/update-payment state
                                  payment-id
                                  (fn [p] (assoc p :returned true)))
           p)))))

(defn return-payment
  "Return a processed payout to its source, telling the source with a
  PAYIN of type `PO_REV` that names the payment it returns and Modulr's
  `reason`, and releasing what the source was waiting for. The payment,
  or `{:refused message}`."
  [state config payment-id reason]
  (let [p (take-back state payment-id)]
    (if (:refused p)
      p
      (let [{:keys [details]} p
            {:keys [sourceAccountId amount currency reference destination]}
            details
            source (ledger/account state sourceAccountId)
            back (ledger/new-payment state
                                     {:status "PROCESSED"
                                      :type "PAYIN"
                                      :details {:accountId sourceAccountId
                                                :amount amount
                                                :reference reference}})]
        (deliveries/notify state
                           (:customer-id source)
                           "PAYIN"
                           (assoc (payin {:payment-id (:id back)
                                          :transaction-id (str "T" (:id back))
                                          :type "PO_REV"
                                          :account source
                                          :payer {:Name (:name destination)}
                                          :amount amount
                                          :currency currency
                                          :reference reference})
                                  :ReturnReason reason
                                  :OriginalSchemeId (scheme-id p)))
        (release-pending state config sourceAccountId)
        p))))

(defn- screen
  [state config p]
  (let [{:keys [sourceAccountId]} (:details p)
        source (ledger/account state sourceAccountId)]
    (pause config)
    (if (= declined-sort-code (get-in p [:details :destination :sortCode]))
      (finish state config p "ER_INVALID")
      (do (deliveries/notify state
                             (:customer-id source)
                             "PAYMENT_COMPLIANCE_STATUS"
                             (compliance source (:id p) "HELD"))
          (pause config)
          (deliveries/notify state
                             (:customer-id source)
                             "PAYMENT_COMPLIANCE_STATUS"
                             (compliance source (:id p) "DECLINED"))
          (finish state config p "CANCELLED")))))

(defn submit
  "Accept a payment, moving its money before answering, so the order
  payments are accepted in is the order their money moves on every
  account they touch, and do the rest in the background, as Modulr
  answers the request before the payment reaches a final status. A
  payment declined or held for compliance moves no money."
  [state config request]
  (let [p (ledger/new-payment state
                              {:status "SUBMITTED"
                               :type "PAYOUT"
                               :externalReference (:externalReference request)
                               :details request})
        {:keys [destination]} request]
    (if (or (= declined-sort-code (:sortCode destination))
            (= held-name (:name destination)))
      (in-background (fn [] (screen state config p)))
      (when (= :short (settle state config p))
        (in-background (fn [] (expire state config (:id p))))))
    p))
