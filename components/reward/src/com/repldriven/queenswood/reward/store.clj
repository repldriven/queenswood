(ns com.repldriven.queenswood.reward.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def ^:private store-name "rewards")

(def transact fdb/transact)

(defn save-reward
  [txn reward]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn store-name)
                                   (schema/Reward->java reward))
                  reward)
                :reward/save
                "Failed to save reward"))

(defn find-by-account
  [txn bank-id account-id kind]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn store-name)]
       (some-> (fdb/query-record-compound
                store
                "Reward"
                [["bank_id" bank-id]
                 ["account_id" account-id]
                 ["kind"
                  (fdb/enum-value store
                                  "Reward"
                                  "kind"
                                  (schema/reward-kind->int kind))]]
                {:index "Reward_by_bank_account"})
               schema/pb->Reward)))
   :reward/find-by-account
   "Failed to find reward by account"))
