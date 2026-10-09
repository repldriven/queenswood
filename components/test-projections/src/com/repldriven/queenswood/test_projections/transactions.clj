(ns com.repldriven.queenswood.test-projections.transactions
  (:require
    [com.repldriven.queenswood.transaction.interface :as transactions]))

(defn project-transactions
  [bank real->bank-id id-mapping]
  (->> id-mapping
       (map (fn [[real-id model-id]]
              [model-id
               (count (transactions/get-transactions bank
                                                     (get real->bank-id
                                                          real-id)
                                                     real-id))]))
       (into {})))

(defn project-model-transactions
  [model-state]
  (->> (:accounts model-state)
       (map (fn [[acct-id acct]]
              [acct-id (or (:transaction-legs acct) 0)]))
       (into {})))
