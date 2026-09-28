(ns com.repldriven.queenswood.form3-relay.outcomes
  (:require
    [clojure.string :as str]))

(def ^:private succeeded #{"delivery_confirmed"})

(def ^:private failed
  #{"delivery_failed" "limit_check_failed" "rejected_by_customer"})

(defn outcome
  [status]
  (cond
   (contains? succeeded status)
   :succeeded

   (contains? failed status)
   :failed

   :else
   nil))

(def ^:private reason-codes
  "ISO 20022 status reasons for Form3's own reasons, `NARR` for any with
  no equivalent."
  {"unknown_accountnumber" "AC01"
   "blocked_account" "AC06"
   "transaction_forbidden" "AG01"
   "duplicate_payment" "AM05"})

(defn reason-code
  [reason]
  (cond
   (nil? reason)
   "NARR"

   (str/starts-with? reason "account_closed")
   "AC04"

   :else
   (get reason-codes reason "NARR")))

(def ^:private admission-reasons
  "Form3's admission reason for each ISO 20022 code the platform refuses
  an inbound with."
  {"AC01" "unknown_accountnumber"
   "AC04" "account_closed"
   "AC06" "blocked_account"
   "AG01" "transaction_forbidden"})

(defn admission-reason
  [code]
  (get admission-reasons code "transaction_forbidden"))

(defn payment
  "The scheme event an outbound's final submission `status` reports,
  keyed on Form3's payment id and the outcome, or nil while it is not
  final."
  [{:keys [provider-payment-id end-to-end-id amount currency status
           status-reason at]}]
  (case (outcome status)
    :succeeded
    {:event-name "transaction-settled"
     :dedup-key (str provider-payment-id ":settled")
     :data {:scheme-transaction-id provider-payment-id
            :end-to-end-id end-to-end-id
            :scheme "fps"
            :debit-credit-code :debit-credit-code-debit
            :amount amount
            :currency currency
            :timestamp-settled at}}

    :failed
    {:event-name "transaction-rejected"
     :dedup-key (str provider-payment-id ":rejected")
     :data {:end-to-end-id end-to-end-id
            :scheme "fps"
            :debit-credit-code :debit-credit-code-debit
            :cancellation-code (reason-code status-reason)
            :failure-kind :failure-kind-declined
            :reason-code (reason-code status-reason)
            :cancellation-reason (or status-reason status)
            :is-return false
            :timestamp-rejected at}}

    nil))
