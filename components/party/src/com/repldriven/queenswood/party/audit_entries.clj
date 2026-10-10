(ns com.repldriven.queenswood.party.audit-entries)

(defn party
  [kind before after data]
  {:bank-id (:bank-id after)
   :kind kind
   :subject-kind :audit-subject-kind-party
   :subject-id (:party-id after)
   :request-id (:request-id data)
   :before before
   :after after
   :performed-by (:actor data)})
