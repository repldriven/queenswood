(ns com.repldriven.queenswood.reward.store
  (:require
    [com.repldriven.queenswood.reward.changelog :as changelog]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(def ^:private store-name "account-rewards")

(def transact fdb/transact)

(defn save-reward
  "Save the row and co-commit its changelog entry, one transaction.
  `changelog` carries `:change-kind` and `:status-before`; the rest
  comes off the row."
  [txn reward changelog]
  (fdb/transact
   txn
   (fn [txn]
     (let-nom>
       [_ (fdb/save-record (fdb/open txn store-name)
                           (schema/AccountReward->java reward))
        entry (changelog/status-changed
               (assoc changelog
                      :bank-id (:bank-id reward)
                      :reward-id (:reward-id reward)
                      :account-id (:account-id reward)
                      :status-after (:status reward)
                      :updated-at (or (:updated-at reward)
                                      (:created-at reward))))
        _ (fdb/write-changelog txn store-name (:reward-id reward) entry)]
       reward))
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
                "AccountReward"
                [["bank_id" bank-id]
                 ["account_id" account-id]
                 ["kind"
                  (fdb/enum-value store
                                  "AccountReward"
                                  "kind"
                                  (schema/reward-kind->int kind))]]
                {:index "AccountReward_by_bank_account"})
               schema/pb->AccountReward)))
   :reward/find-by-account
   "Failed to find reward by account"))
