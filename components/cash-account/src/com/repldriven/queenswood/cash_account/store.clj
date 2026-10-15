(ns com.repldriven.queenswood.cash-account.store
  (:require
    [com.repldriven.queenswood.cash-account.changelog :as changelog]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

;; must match cash-account-query.store/store-name — same FDB store
(def ^:private store-name "cash-accounts")

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-account
  [txn account changelog]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn store-name)]
       (let-nom>
         [_ (fdb/save-record store (schema/CashAccount->java account))
          entry (changelog/account-changed
                 (assoc changelog
                        :bank-id (:bank-id account)
                        :updated-at (or (:updated-at account)
                                        (:created-at account))))
          _ (fdb/write-changelog txn
                                 store-name
                                 (:account-id account)
                                 entry)]
         nil)))
   :cash-account/save
   "Failed to save account"))

;; must match cash-account-query.store/operations-store-name — same FDB store
(def ^:private operations-store-name "cash-account-operations")

(defn save-operation
  [txn operation status-before]
  (fdb/transact
   txn
   (fn [txn]
     (let-nom>
       [_ (fdb/save-record (fdb/open txn operations-store-name)
                           (schema/CashAccountOperation->java operation))
        entry (changelog/operation-changed operation status-before)
        _ (fdb/write-changelog txn
                               operations-store-name
                               (:operation-id operation)
                               entry)]
       nil))
   :cash-account-operation/save
   "Failed to save account operation"))

(defn find-operation
  [txn bank-id account-id operation-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn operations-store-name)
                                           bank-id
                                           account-id
                                           operation-id)
                          schema/pb->CashAccountOperation))
                :cash-account-operation/find
                "Failed to load account operation"))
