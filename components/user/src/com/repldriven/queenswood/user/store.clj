(ns com.repldriven.queenswood.user.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]))

(def ^:private store-name "users")

(def transact fdb/transact)

(defn save
  "Insert or update a User (FDB save-record is upsert by primary key)."
  [txn user]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn store-name)
                                   (schema/User->java user)))
                :user/save
                "Failed to save user"))

(defn get-users
  [txn user-ids]
  (fdb/transact txn
                (fn [txn]
                  (into {}
                        (keep (fn [record]
                                (when record
                                  (let [user (schema/pb->User record)]
                                    [(:user-id user) user]))))
                        (fdb/load-records (fdb/open txn store-name)
                                          (vec user-ids))))
                :user/get-many
                "Failed to load users"))

(defn get-user
  [txn user-id]
  (fdb/transact txn
                (fn [txn]
                  (if-let [record (fdb/load-record (fdb/open txn store-name)
                                                   user-id)]
                    (schema/pb->User record)
                    (error/reject :user/not-found
                                  {:message "User not found"
                                   :user-id user-id})))
                :user/get
                "Failed to load user"))

(defn find-by-sub
  "Returns the User with the given (issuer, sub) pair, or nil if
  absent. Uses the unique User_by_issuer_and_sub index. The OIDC
  spec only guarantees `sub` uniqueness within an `issuer`, so both
  values are required for a safe lookup."
  [txn issuer sub]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/query-record-compound
                           (fdb/open txn store-name)
                           "User"
                           [["issuer" issuer] ["sub" sub]]
                           {:index "User_by_issuer_and_sub"})
                          schema/pb->User))
                :user/find-by-sub
                "Failed to look up user by issuer + sub"))

(defn find-by-email
  "Returns Users with the given email (non-unique index)."
  [txn email]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->User
                        (fdb/query-records
                         (fdb/open txn store-name)
                         "User"
                         "email"
                         email
                         {:index "User_by_email"})))
                :user/find-by-email
                "Failed to look up users by email"))
