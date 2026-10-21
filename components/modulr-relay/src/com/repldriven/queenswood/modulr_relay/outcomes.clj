(ns com.repldriven.queenswood.modulr-relay.outcomes)

(def ^:private succeeded #{"PROCESSED" "RECONCILED"})

(def ^:private failed
  #{"CANCELLED" "ER_INVALID" "ER_EXTCONN" "ER_EXTSYS" "ER_EXPIRED" "ER_GENERAL"
    "ER_BATCH" "VOID"})

(defn outcome
  [status]
  (cond
   (contains? succeeded status)
   :succeeded

   (contains? failed status)
   :failed

   :else
   nil))

(def ^:private return-reasons
  "ISO 20022 return reasons for Modulr's return reasons, `NARR` for any
  with no equivalent."
  {"ACCADDRESSINVALID" "BE04"
   "ACCNOCURRENCY" "AM03"
   "BENACCCLOSED" "AC04"
   "BENACCNAMEBENACCNR" "BE01"
   "BENACCSTOPPED" "AC06"
   "BENDECEASED" "MD07"
   "BENREQ" "MS02"
   "BENSCANUNKNOWN" "AC01"
   "DUPLICATE" "AM05"
   "OTHER" "MS03"
   "RECALL" "FOCR"
   "REGULATION" "RR04"
   "SENDERREQ" "FOCR"
   "SENSITIVITIES" "MS03"
   "TANDC" "AG01"})

(defn return-reason-code
  [code]
  (get return-reasons code "NARR"))

(defn payment
  "The scheme event a payment's final `status` reports, keyed on the
  provider's payment id and the outcome, or nil while it is not final."
  [{:keys [provider-payment-id end-to-end-id amount currency status at]}]
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
            :cancellation-code "NARR"
            :failure-kind :failure-kind-declined
            :reason-code "NARR"
            :cancellation-reason status
            :is-return false
            :timestamp-rejected at}}

    nil))

(defn transfer
  "The transfer event a transfer's final `status` reports, or nil while
  it is not final."
  [{:keys [provider-payment-id transfer-id bank-id status at]}]
  (case (outcome status)
    :succeeded
    {:event-name "transfer-completed"
     :dedup-key (str provider-payment-id ":completed")
     :data {:transfer-id transfer-id :bank-id bank-id :timestamp-completed at}}

    :failed
    {:event-name "transfer-failed"
     :dedup-key (str provider-payment-id ":failed")
     :data {:transfer-id transfer-id
            :bank-id bank-id
            :reason status
            :timestamp-failed at}}

    nil))
