(ns com.repldriven.queenswood.membership-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(def ^:private memberships-store-name "memberships")
(def ^:private invitations-store-name "invitations")
(def ^:private role-changes-store-name "membership-role-changes")

(def transact fdb/transact)

(defn get-membership
  [txn bank-id membership-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn memberships-store-name)
                                           bank-id
                                           membership-id)
                          schema/pb->Membership))
                :membership/get
                "Failed to load membership"))

(defn list-by-user
  [txn user-id]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->Membership
                        (fdb/query-records
                         (fdb/open txn memberships-store-name)
                         "Membership"
                         "user_id"
                         user-id
                         {:index "Membership_by_user"})))
                :membership/list-by-user
                "Failed to list memberships by user"))

(defn find-user-membership
  [txn user-id membership-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/query-record-compound
                           (fdb/open txn memberships-store-name)
                           "Membership"
                           [["user_id" user-id]
                            ["membership_id" membership-id]]
                           {:index "Membership_by_user"})
                          schema/pb->Membership))
                :membership/find-user-membership
                "Failed to load membership"))

(defn list-by-bank
  [txn bank-id]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->Membership
                        (fdb/query-records
                         (fdb/open txn memberships-store-name)
                         "Membership"
                         "bank_id"
                         bank-id)))
                :membership/list-by-bank
                "Failed to list memberships by bank"))

(defn- active
  [memberships]
  (filterv #(= :membership-status-active (:status %)) memberships))

(defn list-active-by-user
  [txn user-id]
  (let-nom> [memberships (list-by-user txn user-id)]
    (active memberships)))

(defn list-active-by-bank
  [txn bank-id]
  (let-nom> [memberships (list-by-bank txn bank-id)]
    (active memberships)))

(defn list-active-by-banks
  [txn bank-ids]
  (fdb/transact txn
                (fn [txn]
                  (reduce (fn [acc bank-id]
                            (let [memberships (list-active-by-bank txn bank-id)]
                              (if (error/anomaly? memberships)
                                (reduced memberships)
                                (assoc acc bank-id memberships))))
                          {}
                          bank-ids))
                :membership/list-by-banks
                "Failed to list memberships by bank"))

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
                  (mapv schema/pb->MembershipRoleChange
                        (fdb/query-records
                         (fdb/open txn role-changes-store-name)
                         "MembershipRoleChange"
                         "bank_id"
                         bank-id)))
                :membership/list-role-changes
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
