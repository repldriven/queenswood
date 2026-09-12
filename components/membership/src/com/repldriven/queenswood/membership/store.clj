(ns com.repldriven.queenswood.membership.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def ^:private store-name "memberships")

(def transact fdb/transact)

(defn create
  [txn membership]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn store-name)
                                   (schema/Membership->java membership)))
                :membership/create
                "Failed to create membership"))

(defn get-membership
  [txn membership-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn store-name)
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
                         (fdb/open txn store-name)
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
                         (fdb/open txn store-name)
                         "Membership"
                         "bank_id"
                         bank-id
                         {:index "Membership_by_bank"})))
                :membership/list-by-bank
                "Failed to list memberships by bank"))
