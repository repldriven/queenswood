(ns com.repldriven.queenswood.form3-adapter.publisher
  "Maps what Form3 holds, read back by the id a notification names, to the
  platform's events, `{:event-name :dedup-key :data}`. An amount that is
  missing, negative or finer than two places is refused as
  `:payment/invalid-scheme-amount`, since mapping it would mean
  guessing."
  (:require
    [com.repldriven.queenswood.form3-relay.interface :as relay]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private max-minor-units (BigDecimal/valueOf Long/MAX_VALUE))

(defn amount->minor-units
  [amount payment-id]
  (let [^BigDecimal minor (when (string? amount)
                            (try (.stripTrailingZeros
                                  (.movePointRight (BigDecimal. ^String amount)
                                                   2))
                                 (catch NumberFormatException _ nil)))]
    (if (or (nil? minor)
            (neg? (.signum minor))
            (pos? (.scale minor))
            (pos? (.compareTo minor max-minor-units)))
      (error/reject :payment/invalid-scheme-amount
                    {:message "Amount is not a non-negative two-place decimal"
                     :amount amount
                     :payment-id payment-id})
      (.longValueExact minor))))

(defn- bban
  [{:keys [bank_id account_number]}]
  (str bank_id account_number))

(defn admission-request
  "The `admit-inbound-payment` a Form3 inbound payment asks for."
  [payment]
  (let [{:keys [id attributes]} payment
        {:keys [amount currency end_to_end_reference reference
                beneficiary_party debtor_party]}
        attributes]
    (let-nom> [amount (amount->minor-units amount id)]
      (utility/assoc-some {:end-to-end-id end_to_end_reference
                           :scheme "fps"
                           :creditor-bban (bban beneficiary_party)
                           :amount amount
                           :currency currency}
                          :debtor-name
                          (or (:account_name debtor_party) (:name debtor_party))
                          :reference
                          reference))))

(defn submission
  "What an outbound's submission reports: a settlement or a rejection
  once its status is final, and nothing before."
  [payment submission at]
  (let [{:keys [id attributes]} payment
        {:keys [amount currency end_to_end_reference]} attributes
        {:keys [status status_reason]} (:attributes submission)]
    (let-nom> [amount (amount->minor-units amount id)]
      (->> [(relay/payment-outcome {:provider-payment-id id
                                    :end-to-end-id end_to_end_reference
                                    :amount amount
                                    :currency currency
                                    :status status
                                    :status-reason status_reason
                                    :at at})]
           (remove nil?)
           vec))))

(defn admitted
  "An inbound Form3 confirmed the admission of: the money has arrived.
  Nothing for one whose admission failed."
  [payment admission at]
  (let [{:keys [id attributes]} payment
        {:keys [amount currency end_to_end_reference reference
                beneficiary_party debtor_party]}
        attributes]
    (if (not= "confirmed" (get-in admission [:attributes :status]))
      []
      (let-nom> [amount (amount->minor-units amount id)]
        [{:event-name "provider-payment-settled"
          :dedup-key (str id ":settled")
          :data (utility/assoc-some {:scheme-transaction-id id
                                     :end-to-end-id end_to_end_reference
                                     :scheme "fps"
                                     :debit-credit-code
                                     :debit-credit-code-credit
                                     :amount amount
                                     :currency currency
                                     :creditor-bban (bban beneficiary_party)
                                     :timestamp-settled at}
                                    :debtor-name
                                    (:account_name debtor_party)
                                    :reference
                                    reference)}]))))

(defn- iso-code?
  [code]
  (boolean (and code (re-matches #"[A-Z]{2}[A-Z0-9]{2}" code))))

(defn returned
  "A return of one of our payments that the beneficiary's bank sent back,
  keyed on the payment's end-to-end id."
  [payment ret at]
  (let [{:keys [end_to_end_reference]} (:attributes payment)
        {:keys [amount currency return_code]} (:attributes ret)]
    (let-nom> [amount (amount->minor-units amount (:id ret))]
      [{:event-name "provider-payment-returned"
        :dedup-key (str end_to_end_reference ":returned")
        :data (utility/assoc-some {:end-to-end-id end_to_end_reference
                                   :scheme "fps"
                                   :debit-credit-code :debit-credit-code-debit
                                   :scheme-transaction-id (:id ret)
                                   :amount amount
                                   :currency currency
                                   :reason-code (if (iso-code? return_code)
                                                  return_code
                                                  "NARR")
                                   :timestamp-returned at}
                                  :reason
                                  return_code)}])))
