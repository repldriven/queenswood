(ns com.repldriven.queenswood.clearbank-adapter.publisher
  "Maps ClearBank webhook payloads to bus-event descriptors
  `{:event-name :dedup-key :data}`. The webhook handler serialises and
  persists these to the outbox; the relay publishes them. Each mapper
  returns a vector of descriptors (one, except assessment-failed which
  fans out per instruction), or a rejection when the payload cannot be
  mapped without guessing: an amount that is missing, negative or has
  more than two decimal places (`:payment/invalid-scheme-amount`), or
  an assessment failure with no instructions
  (`:payment/invalid-assessment-payload`).

  `dedup-key` is the event's logical identity, so a redelivered webhook
  does not double-enqueue:

  - an inbound settlement is `<TransactionId>:settled`, and an inbound
    rejection (the return of a hold) `<TransactionId>:rejected`;
  - an outbound settlement is `<EndToEndTransactionId>:settled`, and an
    outbound rejection `<EndToEndTransactionId>:rejected`;
  - an assessment failure is `<EndToEndId>:rejected` per instruction;
  - an inbound hold is
    `<EndToEndTransactionId>:<BBAN>:<minor units>:<TimestampCreated>:held`;
  - an outbound hold is `<EndToEndTransactionId>:held`.

  An outbound's end-to-end id is the payment id Queenswood issued, so it
  is unique. An inbound's is whatever the sending bank supplied, often
  `NOTPROVIDED`, so two different receipts can share it: an inbound is
  keyed on ClearBank's own `TransactionId`, and a hold, which carries
  none, on the four fields that tell two holds apart."
  (:require
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str])
  (:import
    (java.time Instant)))

(def ^:private max-minor-units (BigDecimal/valueOf Long/MAX_VALUE))

(def ^:private iso-reason-codes
  #{"AB05" "AB06" "AC01" "AC02" "AC03" "AC04" "AC05" "AC06" "AC07" "AC13" "AC14"
    "AG01" "AG02" "AM01" "AM02" "AM03" "AM04" "AM05" "AM06" "AM07" "AM09" "AM10"
    "BE01" "BE04" "BE05" "BE06" "BE07" "CNOR" "DNOR" "DS0A" "DUPL" "ED05" "FF01"
    "FF02" "MD01" "MS02" "MS03" "NARR" "NOAS" "RC01" "RR01" "RR02" "RR03" "RR04"
    "TM01"})

(defn- reason-code
  [cancellation-code]
  (if (contains? iso-reason-codes cancellation-code) cancellation-code "NARR"))

(defn- scheme
  [clearbank-scheme]
  (case clearbank-scheme
    (nil "FasterPayments") "fps"
    clearbank-scheme))

(defn- iso->epoch-millis
  [s]
  (.toEpochMilli (Instant/parse s)))

(defn- ->decimal
  ^BigDecimal [amount]
  (cond
   (instance? BigDecimal amount)
   amount

   (float? amount)
   (BigDecimal/valueOf (double amount))

   (integer? amount)
   (bigdec amount)))

(defn- amount->minor-units
  [amount webhook-id]
  (let [decimal (->decimal amount)
        ^BigDecimal minor-units (when decimal
                                  (.stripTrailingZeros
                                   (.movePointRight decimal 2)))]
    (if (or (nil? minor-units)
            (neg? (.signum minor-units))
            (pos? (.scale minor-units))
            (pos? (.compareTo minor-units max-minor-units)))
      (error/reject :payment/invalid-scheme-amount
                    (merge {:message
                            "Amount is not a non-negative two-place decimal"
                            :amount amount}
                           webhook-id))
      (.longValueExact minor-units))))

(defn inbound-payment-settled
  [payload]
  (let [{:keys [EndToEndTransactionId TransactionId Amount
                CurrencyCode Scheme Reference TimestampSettled
                Account CounterpartAccount]}
        payload
        {:keys [BBAN]} Account
        {:keys [OwnerName]} CounterpartAccount]
    (let-nom>
      [amount (amount->minor-units Amount {:transaction-id TransactionId})]
      [{:event-name "provider-payment-settled"
        :dedup-key (str TransactionId ":settled")
        :data {:scheme-transaction-id TransactionId
               :end-to-end-id EndToEndTransactionId
               :scheme (scheme Scheme)
               :debit-credit-code :debit-credit-code-credit
               :amount amount
               :currency CurrencyCode
               :creditor-bban BBAN
               :debtor-name OwnerName
               :reference Reference
               :timestamp-settled (iso->epoch-millis TimestampSettled)}}])))

(defn outbound-payment-settled
  [payload]
  (let [{:keys [EndToEndTransactionId TransactionId Amount
                CurrencyCode Scheme TimestampSettled]}
        payload]
    (let-nom>
      [amount (amount->minor-units Amount {:transaction-id TransactionId})]
      [{:event-name "provider-payment-settled"
        :dedup-key (str EndToEndTransactionId ":settled")
        :data {:scheme-transaction-id TransactionId
               :end-to-end-id EndToEndTransactionId
               :scheme (scheme Scheme)
               :debit-credit-code :debit-credit-code-debit
               :amount amount
               :currency CurrencyCode
               :timestamp-settled (iso->epoch-millis TimestampSettled)}}])))

(defn- timestamp-rejected
  [timestamp-modified]
  (if timestamp-modified
    (iso->epoch-millis timestamp-modified)
    (utility/now)))

(defn inbound-payment-rejected
  [payload]
  (let [{:keys [TransactionId EndToEndTransactionId Scheme CancellationCode
                CancellationReason IsReturn TimestampModified Account]}
        payload
        {:keys [BBAN]} Account]
    [{:event-name "provider-payment-rejected"
      :dedup-key (str TransactionId ":rejected")
      :data (utility/assoc-some
             {:end-to-end-id EndToEndTransactionId
              :scheme (scheme Scheme)
              :debit-credit-code :debit-credit-code-credit
              :cancellation-code (reason-code CancellationCode)
              :reason-code (reason-code CancellationCode)
              :cancellation-reason CancellationReason
              :is-return IsReturn
              :timestamp-rejected (timestamp-rejected TimestampModified)}
             :creditor-bban
             BBAN)}]))

(defn outbound-payment-rejected
  [payload]
  (let [{:keys [EndToEndTransactionId Scheme CancellationCode
                CancellationReason IsReturn TimestampModified]}
        payload]
    [{:event-name "provider-payment-rejected"
      :dedup-key (str EndToEndTransactionId ":rejected")
      :data {:end-to-end-id EndToEndTransactionId
             :scheme (scheme Scheme)
             :debit-credit-code :debit-credit-code-debit
             :cancellation-code (reason-code CancellationCode)
             :failure-kind :failure-kind-declined
             :reason-code (reason-code CancellationCode)
             :cancellation-reason CancellationReason
             :is-return IsReturn
             :timestamp-rejected (timestamp-rejected TimestampModified)}}]))

(defn outbound-payment-assessment-failed
  "ClearBank assessed the message and rejected it before settlement. One
  webhook can carry several failed instructions; each `EndToEndId` is an
  OutboundPayment to reverse. Routed through `provider-payment-rejected` — the
  pre-flight failure has the same outcome as a scheme decline. Fans out
  to one descriptor per instruction, read from `AssessmentFailure` or
  from ClearBank's own spelling, `AssesmentFailure`."
  [payload]
  (let [{:keys [MessageId PaymentMethodType AssessmentFailure
                AssesmentFailure]}
        payload
        instructions (or (not-empty AssessmentFailure)
                         (not-empty AssesmentFailure))]
    (if (nil? instructions)
      (error/reject :payment/invalid-assessment-payload
                    {:message "Assessment failure lists no instructions"
                     :message-id MessageId})
      (mapv
       (fn [{:keys [EndToEndId Reasons]}]
         {:event-name "provider-payment-rejected"
          :dedup-key (str EndToEndId ":rejected")
          :data {:end-to-end-id EndToEndId
                 :scheme (scheme PaymentMethodType)
                 :debit-credit-code :debit-credit-code-debit
                 :cancellation-code "NARR"
                 :failure-kind :failure-kind-declined
                 :reason-code "NARR"
                 :cancellation-reason (str/join "; " Reasons)
                 :is-return false
                 :timestamp-rejected (utility/now)}})
       instructions))))

(defn inbound-payment-held
  [payload]
  (let [{:keys [EndToEndTransactionId TransactionAmount
                Scheme TimestampCreated Account]}
        payload
        {:keys [BBAN]} Account]
    (let-nom>
      [amount (amount->minor-units TransactionAmount
                                   {:end-to-end-id EndToEndTransactionId})]
      [{:event-name "provider-payment-held"
        :dedup-key (str/join ":"
                             [EndToEndTransactionId BBAN amount
                              TimestampCreated "held"])
        :data {:end-to-end-id EndToEndTransactionId
               :scheme (scheme Scheme)
               :debit-credit-code :debit-credit-code-credit
               :amount amount
               :currency "GBP"
               :creditor-bban BBAN
               :timestamp-held (iso->epoch-millis TimestampCreated)}}])))

(defn outbound-payment-held
  [payload]
  (let [{:keys [EndToEndTransactionId TransactionAmount
                Scheme TimestampCreated CounterpartAccount]}
        payload
        {:keys [BBAN]} CounterpartAccount]
    (let-nom>
      [amount (amount->minor-units TransactionAmount
                                   {:end-to-end-id EndToEndTransactionId})]
      [{:event-name "provider-payment-held"
        :dedup-key (str EndToEndTransactionId ":held")
        :data {:end-to-end-id EndToEndTransactionId
               :scheme (scheme Scheme)
               :debit-credit-code :debit-credit-code-debit
               :amount amount
               :currency "GBP"
               :creditor-bban BBAN
               :timestamp-held (iso->epoch-millis TimestampCreated)}}])))
