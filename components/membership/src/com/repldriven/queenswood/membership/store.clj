(ns com.repldriven.queenswood.membership.store
  (:require
    [com.repldriven.queenswood.membership.changelog :as changelog]

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

(defn save-invitation
  "Save `invitation`, and when `event-name` is given co-commit its
  changelog entry under the invitations store."
  ([txn invitation]
   (save-invitation txn invitation nil))
  ([txn invitation event-name]
   (fdb/transact
    txn
    (fn [txn]
      (let-nom>
        [_ (fdb/save-record (fdb/open txn invitations-store-name)
                            (schema/Invitation->java invitation))
         _ (when event-name
             (let-nom> [entry (changelog/invitation-changed event-name
                                                            invitation)]
               (fdb/write-changelog txn
                                    invitations-store-name
                                    (:invitation-id invitation)
                                    entry)))]
        invitation))
    :invitation/save
    "Failed to save invitation")))

(defn save-access-event
  [txn access-event]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn access-events-store-name)
                                   (schema/AccessEvent->java access-event)))
                :access-event/save
                "Failed to save access event"))
