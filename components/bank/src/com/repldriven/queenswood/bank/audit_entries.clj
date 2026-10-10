(ns com.repldriven.queenswood.bank.audit-entries)

(defn bank
  [kind before after actor request-id]
  {:bank-id (:bank-id after)
   :kind kind
   :subject-kind :audit-subject-kind-bank
   :subject-id (:bank-id after)
   :request-id request-id
   :before before
   :after after
   :performed-by actor})
