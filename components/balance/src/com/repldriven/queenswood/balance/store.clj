(ns com.repldriven.queenswood.balance.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

;; must match bank-balance-query.store/store-name — same FDB store
(def ^:private store-name "balances")

(def transact fdb/transact)

(defn save-balance
  [txn balance]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn store-name)
                      (schema/Balance->java balance)))
   :balance/save
   "Failed to save balance"))

(defn save-balances
  [txn balances]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn store-name)]
       (fdb/save-records (mapv (fn [balance]
                                 [store (schema/Balance->java balance)])
                               balances))))
   :balance/save
   "Failed to save balances"))
