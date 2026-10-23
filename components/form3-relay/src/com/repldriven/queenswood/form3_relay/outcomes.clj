(ns com.repldriven.queenswood.form3-relay.outcomes
  (:require
    [com.repldriven.mono.utility.interface :as utility]))

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

(def ^:private scheme-reasons
  "ISO 20022 status reasons for the Faster Payments reject codes Form3
  reports as a failed submission's `scheme_status_code`, `NARR` for any
  with no equivalent."
  {"1100" "MS03"
   "1114" "AC03"
   "1160" "AC07"
   "1161" "AC06"
   "1162" "BE01"
   "1165" "AC09"
   "1167" "MD07"
   "1170" "AG01"
   "1171" "RR03"
   "1172" "AM04"
   "1173" "AC02"
   "1174" "AC05"
   "1175" "AC06"
   "1176" "AC03"
   "1177" "AC07"
   "1178" "AC06"
   "1181" "AM05"
   "1930" "FF01"
   "9908" "CNOR"
   "9911" "AB05"
   "9913" "AM05"
   "9930" "FF01"
   "9940" "AM13"
   "9941" "AM02"})

(defn reason-code
  [scheme-status-code]
  (get scheme-reasons scheme-status-code "NARR"))

(def ^:private return-reasons
  "ISO 20022 return reasons for the Faster Payments return codes Form3
  carries as a return's `return_code`, `NARR` for any with no
  equivalent."
  {"00000001" "AC01"
   "00000002" "AC04"
   "00000003" "AC06"
   "00000004" "MD07"
   "00000006" "BE01"
   "00000007" "FOCR"
   "00000008" "AM03"
   "00000009" "MS02"
   "00000010" "AG01"
   "00000013" "MS03"
   "00000014" "MS03"})

(defn return-reason-code
  [return-code]
  (get return-reasons return-code "NARR"))

(def ^:private return-codes
  "The Faster Payments return code Form3 takes for each ISO 20022 code
  the platform parks an inbound with, `00000014`, other, for the rest."
  {"AC01" "00000001"
   "AC04" "00000002"
   "AC06" "00000003"
   "AM03" "00000008"
   "AG01" "00000010"})

(defn return-code
  [code]
  (get return-codes code "00000014"))

(def ^:private admission-reasons
  "Form3's Faster Payments admission reason for each ISO 20022 code the
  platform refuses an inbound with."
  {"AC01" "unknown_accountnumber"
   "AC04" "account_closed"
   "AC06" "account_closed_stopped"
   "AM03" "account_closed_currency"
   "AG01" "account_closed_terms_and_conditions"})

(defn admission-reason
  [code]
  (get admission-reasons code "transaction_forbidden"))

(defn- provider-reason
  "Form3's own code with its description, or whichever it gave."
  [code description]
  (if (and code description)
    (str code ": " description)
    (or code description)))

(defn payment
  "The scheme event an outbound's final submission `status` reports,
  keyed on Form3's payment id and the outcome, or nil while it is not
  final."
  [{:keys [provider-payment-id end-to-end-id amount currency status
           status-reason scheme-status-code scheme-status-description at]}]
  (case (outcome status)
    :succeeded
    {:event-name "provider-payment-settled"
     :dedup-key (str provider-payment-id ":settled")
     :data {:scheme-transaction-id provider-payment-id
            :end-to-end-id end-to-end-id
            :scheme "fps"
            :debit-credit-code :debit-credit-code-debit
            :amount amount
            :currency currency
            :timestamp-settled at}}

    :failed
    {:event-name "provider-payment-rejected"
     :dedup-key (str provider-payment-id ":rejected")
     :data {:end-to-end-id end-to-end-id
            :scheme "fps"
            :debit-credit-code :debit-credit-code-debit
            :cancellation-code (reason-code scheme-status-code)
            :failure-kind :failure-kind-declined
            :reason-code (reason-code scheme-status-code)
            :cancellation-reason (or (provider-reason scheme-status-code
                                                      (or
                                                       scheme-status-description
                                                       status-reason))
                                     status)
            :is-return false
            :timestamp-rejected at}}

    nil))

(defn returned
  "The scheme event a return's final submission `status` reports for the
  inbound it sent back, keyed on Form3's id for that inbound, or nil
  while it is not final or where it failed."
  [{:keys [provider-payment-id end-to-end-id amount currency reason-code
           reason status at]}]
  (when (= :succeeded (outcome status))
    {:event-name "provider-payment-returned"
     :dedup-key (str provider-payment-id ":returned")
     :data (utility/assoc-some {:end-to-end-id end-to-end-id
                                :scheme "fps"
                                :debit-credit-code :debit-credit-code-credit
                                :scheme-transaction-id provider-payment-id
                                :amount amount
                                :currency currency
                                :reason-code reason-code
                                :timestamp-returned at}
                               :reason
                               reason)}))
