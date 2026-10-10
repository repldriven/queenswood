(ns com.repldriven.queenswood.bank.audit-events)

(defn bank
  [kind bank actor request-id details]
  {:bank-id (:bank-id bank)
   :kind kind
   :subject-kind :audit-subject-kind-bank
   :subject-id (:bank-id bank)
   :request-id request-id
   :details details
   :created-by actor})
