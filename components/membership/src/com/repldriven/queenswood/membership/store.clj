(ns com.repldriven.queenswood.membership.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(def ^:private memberships-store-name "memberships")
(def ^:private invitations-store-name "invitations")
(def ^:private access-events-store-name "access-events")

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-membership
  [txn membership]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn memberships-store-name)
                                   (schema/Membership->java membership)))
                :membership/save
                "Failed to save membership"))

(defn get-membership
  [txn membership-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn memberships-store-name)
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

(defn list-by-bank
  [txn bank-id]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->Membership
                        (fdb/query-records
                         (fdb/open txn memberships-store-name)
                         "Membership"
                         "bank_id"
                         bank-id
                         {:index "Membership_by_bank"})))
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

(defn save-invitation
  [txn invitation]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn invitations-store-name)
                                   (schema/Invitation->java invitation)))
                :invitation/save
                "Failed to save invitation"))

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

(defn list-invitations-by-bank
  [txn bank-id]
  (fdb/transact txn
                (fn [txn]
                  (into []
                        (comp (map schema/pb->Invitation)
                              (remove #(contains? closed-statuses
                                                  (:status %))))
                        (fdb/query-records
                         (fdb/open txn invitations-store-name)
                         "Invitation"
                         "bank_id"
                         bank-id
                         {:index "Invitation_by_bank"})))
                :invitation/list-by-bank
                "Failed to list invitations by bank"))

(defn save-access-event
  [txn access-event]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn access-events-store-name)
                                   (schema/AccessEvent->java access-event)))
                :access-event/save
                "Failed to save access event"))

(defn scan-access-events
  [txn bank-id opts]
  (let [{:keys [after before limit order]
         :or {limit 100 order :desc}}
        opts]
    (let-nom>
      [result (fdb/transact txn
                            (fn [txn]
                              (fdb/scan-records
                               (fdb/open txn access-events-store-name)
                               {:prefix [bank-id]
                                :after after
                                :before before
                                :limit limit
                                :order order}))
                            :access-event/list
                            "Failed to list access events")
       {:keys [records before after]} result]
      {:access-events (mapv schema/pb->AccessEvent records)
       :before before
       :after after})))
