(ns com.repldriven.queenswood.member.audit-entries)

(defn- entry
  [kind subject-kind subject-id bank-id opts now before after]
  {:bank-id bank-id
   :kind kind
   :subject-kind subject-kind
   :subject-id subject-id
   :request-id (:request-id opts)
   :before before
   :after after
   :performed-at now
   :performed-by (:actor opts)})

(defn invitation
  [kind before after opts now]
  (let [{:keys [bank-id invitation-id]} after]
    (entry kind
           :audit-subject-kind-invitation
           invitation-id
           bank-id
           opts
           now
           before
           after)))

(defn member
  [kind before after opts now]
  (let [{:keys [bank-id member-id]} after]
    (entry kind
           :audit-subject-kind-member
           member-id
           bank-id
           opts
           now
           before
           after)))
