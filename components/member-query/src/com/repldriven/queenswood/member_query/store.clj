(ns com.repldriven.queenswood.member-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(def ^:private members-store-name "members")
(def ^:private invitations-store-name "invitations")
(def ^:private role-changes-store-name "member-role-changes")

(def transact fdb/transact)

(defn get-member
  [txn bank-id member-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn members-store-name)
                                           bank-id
                                           member-id)
                          schema/pb->Member))
                :member/get
                "Failed to load member"))

(defn list-by-user
  [txn user-id]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->Member
                        (fdb/query-records
                         (fdb/open txn members-store-name)
                         "Member"
                         "user_id"
                         user-id
                         {:index "Member_by_user"})))
                :member/list-by-user
                "Failed to list members by user"))

(defn find-user-member
  [txn user-id member-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/query-record-compound
                           (fdb/open txn members-store-name)
                           "Member"
                           [["user_id" user-id]
                            ["member_id" member-id]]
                           {:index "Member_by_user"})
                          schema/pb->Member))
                :member/find-user-member
                "Failed to load member"))

(defn list-by-bank
  [txn bank-id]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->Member
                        (fdb/query-records
                         (fdb/open txn members-store-name)
                         "Member"
                         "bank_id"
                         bank-id)))
                :member/list-by-bank
                "Failed to list members by bank"))

(defn- active
  [members]
  (filterv #(= :member-status-active (:status %)) members))

(defn list-active-by-user
  [txn user-id]
  (let-nom> [members (list-by-user txn user-id)]
    (active members)))

(defn list-active-by-bank
  [txn bank-id]
  (let-nom> [members (list-by-bank txn bank-id)]
    (active members)))

(defn list-active-by-banks
  [txn bank-ids]
  (fdb/transact txn
                (fn [txn]
                  (reduce (fn [acc bank-id]
                            (let [members (list-active-by-bank txn bank-id)]
                              (if (error/anomaly? members)
                                (reduced members)
                                (assoc acc bank-id members))))
                          {}
                          bank-ids))
                :member/list-by-banks
                "Failed to list members by bank"))

(defn find-invitation
  [txn bank-id invitation-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn invitations-store-name)
                                           bank-id
                                           invitation-id)
                          schema/pb->Invitation))
                :invitation/find
                "Failed to load invitation"))

(defn find-invitation-by-token-hash
  [txn token-hash]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/query-record
                           (fdb/open txn invitations-store-name)
                           "Invitation"
                           "token_hash"
                           token-hash
                           {:index "Invitation_by_token_hash"})
                          schema/pb->Invitation))
                :invitation/find-by-token-hash
                "Failed to find invitation by token"))

(defn list-invitations-by-email
  [txn email-lower]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->Invitation
                        (fdb/query-records
                         (fdb/open txn invitations-store-name)
                         "Invitation"
                         "email_lower"
                         email-lower
                         {:index "Invitation_by_email"})))
                :invitation/list-by-email
                "Failed to list invitations by email"))

(def ^:private closed-statuses
  #{:invitation-status-declined :invitation-status-withdrawn})

(defn list-all-invitations-by-bank
  [txn bank-id]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->Invitation
                        (fdb/query-records (fdb/open txn invitations-store-name)
                                           "Invitation"
                                           "bank_id"
                                           bank-id)))
                :invitation/list-by-bank
                "Failed to list invitations by bank"))

(defn list-invitations-by-bank
  [txn bank-id]
  (let-nom> [invitations (list-all-invitations-by-bank txn bank-id)]
    (into []
          (remove #(contains? closed-statuses (:status %)))
          invitations)))

(defn list-role-changes-by-bank
  [txn bank-id]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->MemberRoleChange
                        (fdb/query-records
                         (fdb/open txn role-changes-store-name)
                         "MemberRoleChange"
                         "bank_id"
                         bank-id)))
                :member/list-role-changes
                "Failed to list role changes"))

(defn- open-invitations
  [entries]
  (into []
        (comp (map (fn [{:keys [key record]}]
                     {:key key :invitation (schema/pb->Invitation record)}))
              (remove #(contains? closed-statuses (:status (:invitation %)))))
        entries))

(defn page-invitations-by-bank
  [txn bank-id opts]
  (let [{:keys [after before limit order] :or {limit 100 order :desc}} opts
        back? (some? before)
        scan (fn [store cursor]
               (fdb/scan-record-entries store
                                        (assoc {:prefix [bank-id]
                                                :limit limit
                                                :order order}
                                               (if back? :before :after)
                                               cursor)))]
    (fdb/transact
     txn
     (fn [txn]
       ;; Declined and withdrawn invitations are skipped, so a scan can
       ;; come back short; scanning on from where it stopped fills the
       ;; page rather than returning fewer than `limit`.
       (let [store (fdb/open txn invitations-store-name)]
         (loop [cursor (if back? before after)
                kept []]
           (let [found (scan store cursor)
                 opened (open-invitations (:entries found))
                 kept (if back? (into opened kept) (into kept opened))
                 further (if back? (:before found) (:after found))]
             (if (and further (< (count kept) limit))
               (recur further kept)
               (let [more? (or (some? further) (> (count kept) limit))
                     page (if back?
                            (vec (take-last limit kept))
                            (vec (take limit kept)))]
                 {:invitations (mapv :invitation page)
                  :before (when (and (seq page) (if back? more? after))
                            (:key (first page)))
                  :after (when (and (seq page) (if back? true more?))
                           (:key (peek page)))}))))))
     :invitation/list-by-bank
     "Failed to list invitations by bank")))
