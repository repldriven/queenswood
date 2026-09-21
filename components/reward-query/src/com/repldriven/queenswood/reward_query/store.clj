(ns com.repldriven.queenswood.reward-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

;; must match reward.store — the same FDB store
(def ^:private store-name "rewards")

(defn find-reward
  [txn bank-id reward-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn store-name)
                                           bank-id
                                           reward-id)
                          schema/pb->Reward))
                :reward/find
                "Failed to find reward"))

(defn find-rewards-by-account
  [txn bank-id account-id]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->Reward
                        (fdb/query-records-compound
                         (fdb/open txn store-name)
                         "Reward"
                         [["bank_id" bank-id] ["account_id" account-id]]
                         {:index "Reward_by_bank_account"})))
                :reward/find-by-account
                "Failed to find rewards by account"))
