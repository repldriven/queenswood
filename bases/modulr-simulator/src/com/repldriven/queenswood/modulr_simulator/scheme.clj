(ns com.repldriven.queenswood.modulr-simulator.scheme
  "What happens to a payment once Modulr has accepted it. The test values
  every payment simulator shares decide an outcome before any money
  moves: a creditor sort code `000000` is declined, and the creditor
  name `6a41a29eafcf455493` held for compliance then declined. Otherwise
  a payment the source account cannot cover waits `PENDING_FOR_FUNDS`
  until money arrives or `pending-for-funds-ms` passes, when it expires
  without a notification, as Modulr's do. A payment it can cover is
  processed: the source is debited and told, and where the destination
  is an account the simulator holds, it is credited and told. A payment
  to an account the simulator has closed is returned to its source, as
  the beneficiary's bank would, and a control route returns any other
  processed payment the same way."
  (:require
    [com.repldriven.queenswood.modulr-simulator.deliveries :as deliveries]
    [com.repldriven.queenswood.modulr-simulator.ledger :as ledger]

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

(defn- arrive
  "Credit the destination, where the simulator holds it, and tell it; or
  return the payment where the simulator has closed it."
  [state config p]
  (let [{:keys [details externalReference]} p
        {:keys [destination amount currency reference sourceAccountId]} details
        target (destination-account state destination)
        source (ledger/account state sourceAccountId)]
    (cond
     (nil? target)
     nil

     (= "CLOSED" (:status target))
     (do (pause config) (return-payment state config (:id p) "BENACCCLOSED"))

     :else
     (do
       (ledger/credit state (:id target) amount)
       (ledger/update-payment state
                              (:id p)
                              (fn [p] (assoc p :arrived-at (:id target))))
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
       (release-pending state config (:id target))))))

(def ^:private unsettled #{"SUBMITTED" "PENDING_FOR_FUNDS"})

(defn- take-funds
  "Debit the source and mark the payment processed, as one step, or mark
  it waiting for funds and return `:short` where the source cannot cover
  it. Nil for a payment another thread already settled."
  [state p]
  (locking state
    (let [{:keys [status details]} (ledger/payment state (:id p))
          {:keys [sourceAccountId amount]} details]
      (cond
       (not (contains? unsettled status))
       nil

       (ledger/debit state sourceAccountId amount)
       (ledger/update-payment state
                              (:id p)
                              (fn [p]
                                (assoc p
                                       :status "PROCESSED"
                                       :transaction-id (str "T" (:id p))
                                       :schemeId (str scheme-id-prefix
                                                      (scheme-id p)))))

       :else
       (do (ledger/update-payment state
                                  (:id p)
                                  (fn [p]
                                    (assoc p :status "PENDING_FOR_FUNDS")))
           :short)))))

(defn- settle
  [state config p]
  (let [taken (take-funds state p)]
    (if (map? taken)
      (do (finish state config taken "PROCESSED")
          (arrive state config taken)
          :processed)
      taken)))

(defn- expire
  [state config payment-id]
  (Thread/sleep (long (or (:pending-for-funds-ms config)
                          default-pending-for-funds-ms)))
  (let [p (ledger/payment state payment-id)]
    (when (= "PENDING_FOR_FUNDS" (:status p))
      (log/info "Modulr simulator payment expired waiting for funds"
                {:payment-id payment-id})
      (ledger/update-payment state
                             payment-id
                             (fn [p] (assoc p :status "ER_EXPIRED"))))))

(defn release-pending
  "Process, in the order they arrived, the payments waiting for funds on
  `account-id` that its balance now covers."
  [state config account-id]
  (doseq [p (ledger/pending-for-funds state account-id)]
    (settle state config p)))

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
  `reason`. The payment, or `{:refused message}`."
  [state _config payment-id reason]
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
        p))))

(defn process
  [state config payment-id]
  (let [p (ledger/payment state payment-id)
        {:keys [destination sourceAccountId]} (:details p)
        source (ledger/account state sourceAccountId)]
    (pause config)
    (cond
     (= declined-sort-code (:sortCode destination))
     (finish state config p "ER_INVALID")

     (= held-name (:name destination))
     (do (deliveries/notify state
                            (:customer-id source)
                            "PAYMENT_COMPLIANCE_STATUS"
                            (compliance source (:id p) "HELD"))
         (pause config)
         (deliveries/notify state
                            (:customer-id source)
                            "PAYMENT_COMPLIANCE_STATUS"
                            (compliance source (:id p) "DECLINED"))
         (finish state config p "CANCELLED"))

     (= :short (settle state config p))
     (future (expire state config (:id p))))))

(defn submit
  "Accept a payment and process it on another thread, as Modulr answers
  the request before the payment reaches a final status."
  [state config request]
  (let [p (ledger/new-payment state
                              {:status "SUBMITTED"
                               :type "PAYOUT"
                               :externalReference (:externalReference request)
                               :details request})]
    (future (try (process state config (:id p))
                 (catch Exception e
                   (log/error e "Modulr simulator payment processing threw"))))
    p))
