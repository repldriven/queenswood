(ns com.repldriven.queenswood.member.store
  (:require
    [com.repldriven.queenswood.member.changelog :as changelog]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(def ^:private members-store-name "members")
(def ^:private invitations-store-name "invitations")
(def ^:private role-changes-store-name "member-role-changes")

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-member
  [txn member]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn members-store-name)
                                   (schema/Member->java member)))
                :member/save
                "Failed to save member"))

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

(defn save-role-change
  [txn role-change]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn role-changes-store-name)
                                   (schema/MemberRoleChange->java
                                    role-change)))
                :member/save-role-change
                "Failed to save role change"))
