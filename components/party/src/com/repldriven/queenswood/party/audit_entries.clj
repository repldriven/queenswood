(ns com.repldriven.queenswood.party.audit-entries)

(defn party
  [kind party data details]
  {:bank-id (:bank-id party)
   :kind kind
   :subject-kind :audit-subject-kind-party
   :subject-id (:party-id party)
   :request-id (:request-id data)
   :details details
   :created-by (:actor data)})
