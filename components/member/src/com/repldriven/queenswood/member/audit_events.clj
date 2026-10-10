(ns com.repldriven.queenswood.member.audit-events)

(defn- event
  [kind subject-kind subject-id bank-id opts now details]
  {:bank-id bank-id
   :kind kind
   :subject-kind subject-kind
   :subject-id subject-id
   :request-id (:request-id opts)
   :details (into {} (filter (comp some? val)) details)
   :created-at now
   :created-by (:actor opts)})

(defn invitation
  [kind invitation opts now details]
  (let [{:keys [bank-id invitation-id role]} invitation]
    (event kind
           :audit-subject-kind-invitation
           invitation-id
           bank-id
           opts
           now
           (merge {:role-after role} details))))

(defn member
  [kind member opts now details]
  (let [{:keys [bank-id member-id user-id]} member]
    (event kind
           :audit-subject-kind-member
           member-id
           bank-id
           opts
           now
           (merge {:subject-user-id user-id} details))))
