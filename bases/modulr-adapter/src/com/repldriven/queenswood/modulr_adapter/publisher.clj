(ns com.repldriven.queenswood.modulr-adapter.publisher
  "Maps Modulr's notifications to the platform's events,
  `{:event-name :dedup-key :data}`, each keyed on Modulr's payment id
  and the outcome, as a reconciliation keys the same outcome. An amount
  that is missing, negative or finer than two places is refused as
  `:payment/invalid-scheme-amount`, since mapping it would mean
  guessing."
  (:require
    [com.repldriven.queenswood.modulr-relay.interface :as relay]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility])
  (:import
    (java.time OffsetDateTime)
    (java.time.format DateTimeFormatter)))

(def ^:private max-minor-units (BigDecimal/valueOf Long/MAX_VALUE))

(def ^:private ^DateTimeFormatter timestamp-format
  (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss[.SSS][.SS][.S]Z"))

(defn- ->decimal
  ^BigDecimal [amount]
  (cond
   (instance? BigDecimal amount)
   amount

   (string? amount)
   (try (BigDecimal. ^String amount)
        (catch NumberFormatException _ nil))

   (float? amount)
   (BigDecimal/valueOf (double amount))

   (integer? amount)
   (bigdec amount)))

(defn amount->minor-units
  [amount payment-id]
  (let [decimal (->decimal amount)
        ^BigDecimal minor (when decimal
                            (.stripTrailingZeros (.movePointRight decimal 2)))]
    (if (or (nil? minor)
            (neg? (.signum minor))
            (pos? (.scale minor))
            (pos? (.compareTo minor max-minor-units)))
      (error/reject :payment/invalid-scheme-amount
                    {:message "Amount is not a non-negative two-place decimal"
                     :amount amount
                     :payment-id payment-id})
      (.longValueExact minor))))

(defn epoch-millis
  "The epoch millis of a Modulr timestamp, or now where it cannot be
  read, since Modulr's examples are not all well-formed."
  [s]
  (or (when s
        (try (.toEpochMilli (.toInstant (OffsetDateTime/parse
                                         s
                                         timestamp-format)))
             (catch Exception _ nil)))
      (utility/now)))

(defn- bban
  [{:keys [SortCode AccountNumber sortCode accountNumber]}]
  (when-let [sort-code (or SortCode sortCode)]
    (str sort-code (or AccountNumber accountNumber))))

(defn inbound
  "A PAYIN from outside: the payment settled into one of our accounts."
  [payin]
  (let [{:keys [PaymentId Amount Currency Payee PayerName PaymentReference
                PaymentAppliedTime DateTime]}
        payin]
    (let-nom> [amount (amount->minor-units Amount PaymentId)]
      [(utility/assoc-some
        {:event-name "transaction-settled"
         :dedup-key (str PaymentId ":settled")
         :data {:scheme-transaction-id PaymentId
                :end-to-end-id PaymentId
                :scheme "fps"
                :debit-credit-code :debit-credit-code-credit
                :amount amount
                :currency Currency
                :creditor-bban (bban (:Identifier Payee))
                :debtor-name PayerName
                :reference PaymentReference
                :timestamp-settled (epoch-millis (or PaymentAppliedTime
                                                     DateTime))}})])))

(defn returned
  "A PAYIN of type `PO_REV`: the scheme brought back an outbound payment.
  Reported against the payment it returns where `original`, the payment
  Modulr names as returned, was one the adapter submitted for the
  platform, and otherwise as money arriving at the account it landed
  in, so it is never lost."
  [payin original intent]
  (let [{:keys [PaymentId Amount Currency ReturnReason PaymentAppliedTime
                DateTime]}
        payin
        end-to-end-id (relay/reference->id (:externalReference original))]
    (if-not (and end-to-end-id
                 (= :modulr-outbound-intent-kind-payment (:kind intent)))
      (inbound payin)
      (let-nom> [amount (amount->minor-units Amount PaymentId)]
        [{:event-name "transaction-returned"
          :dedup-key (str end-to-end-id ":returned")
          :data (utility/assoc-some
                 {:end-to-end-id end-to-end-id
                  :scheme "fps"
                  :debit-credit-code :debit-credit-code-debit
                  :scheme-transaction-id PaymentId
                  :amount amount
                  :currency Currency
                  :reason-code (relay/reason-code ReturnReason)
                  :timestamp-returned (epoch-millis (or PaymentAppliedTime
                                                        DateTime))}
                 :reason
                 ReturnReason)}]))))

(defn payout
  "A PAYOUT at a final status, for a payment or a transfer the adapter
  made; nothing while the status is not final."
  [payout intent]
  (let [{:keys [PaymentId Status Amount ExternalReference EventTime]} payout
        {:keys [kind context]} intent
        at (epoch-millis EventTime)]
    (let-nom> [amount (amount->minor-units Amount PaymentId)]
      (->> [(if (= :modulr-outbound-intent-kind-transfer kind)
              (relay/transfer-outcome {:provider-payment-id PaymentId
                                       :transfer-id (:idempotency-key intent)
                                       :bank-id (:bank-id context)
                                       :status Status
                                       :at at})
              (relay/payment-outcome {:provider-payment-id PaymentId
                                      :end-to-end-id (relay/reference->id
                                                      ExternalReference)
                                      :amount amount
                                      :currency (or (:currency context) "GBP")
                                      :status Status
                                      :at at}))]
           (remove nil?)
           vec))))

(defn- held
  [payment debit-credit-code creditor-bban end-to-end-id at]
  (let [{:keys [id details]} payment
        {:keys [amount currency]} details]
    (let-nom> [amount (amount->minor-units amount id)]
      [{:event-name "transaction-held"
        :dedup-key (str id ":held")
        :data {:end-to-end-id end-to-end-id
               :scheme "fps"
               :debit-credit-code debit-credit-code
               :amount amount
               :currency (or currency "GBP")
               :creditor-bban creditor-bban
               :timestamp-held at}}])))

(defn- rejected
  [payment debit-credit-code creditor-bban end-to-end-id reason at]
  [{:event-name "transaction-rejected"
    :dedup-key (str (:id payment) ":rejected")
    :data (utility/assoc-some
           {:end-to-end-id end-to-end-id
            :scheme "fps"
            :debit-credit-code debit-credit-code
            :cancellation-code "NARR"
            :failure-kind :failure-kind-declined
            :reason-code "NARR"
            :cancellation-reason reason
            :is-return (= :debit-credit-code-credit debit-credit-code)
            :timestamp-rejected at}
           :creditor-bban
           creditor-bban)}])

(defn compliance
  "A PAYMENTCOMPLIANCESTATUS, read beside the payment Modulr holds: a
  hold on either side, an outbound declined, or an inbound returned to
  its sender. A release reports nothing, since the PAYIN or PAYOUT that
  follows it does."
  [notification payment]
  (let [{:keys [ComplianceStatus EventTime]} notification
        {:keys [id type externalReference details]} payment
        at (epoch-millis EventTime)
        outbound? (= "PAYOUT" type)
        code (if outbound? :debit-credit-code-debit :debit-credit-code-credit)
        creditor-bban (bban (if outbound?
                              (:destination details)
                              (:payee details)))
        end-to-end-id (if outbound? (relay/reference->id externalReference) id)]
    (case ComplianceStatus
      "HELD"
      (held payment code creditor-bban end-to-end-id at)

      ("DECLINED" "RETURNED")
      (rejected payment
                code
                (when-not outbound? creditor-bban)
                end-to-end-id
                (str "Compliance " ComplianceStatus)
                at)

      [])))
