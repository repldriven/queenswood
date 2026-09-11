(ns com.repldriven.queenswood.cash-account-query.core
  (:require
    [com.repldriven.queenswood.cash-account-query.store :as store]

    [com.repldriven.queenswood.balance-query.interface :as balances]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- enrich-account
  [txn opts account]
  (let [{:keys [bank-id account-id]} account]
    (let-nom>
      [balances (when (:embed-balances opts)
                  (balances/get-balances txn bank-id account-id))
       transactions (when (:embed-transactions opts)
                      (transactions/get-transactions txn account-id))]
      (cond-> account

              balances
              (merge balances)

              transactions
              (assoc :transactions transactions)))))

(defn get-account
  ([txn bank-id account-id]
   (get-account txn bank-id account-id nil))
  ([txn bank-id account-id opts]
   (store/transact
    txn
    (fn [txn]
      (let-nom>
        [account (store/find-account txn bank-id account-id)
         account (or account
                     (error/reject :cash-account/not-found
                                   {:message "Account not found"
                                    :bank-id bank-id
                                    :account-id account-id}))]
        (enrich-account txn opts account))))))

(defn get-accounts
  ([txn bank-id]
   (get-accounts txn bank-id nil))
  ([txn bank-id opts]
   (store/transact
    txn
    (fn [txn]
      (let-nom>
        [{:keys [accounts before after]} (store/get-accounts txn bank-id opts)
         enriched (reduce (fn [acc account]
                            (let [result (enrich-account txn opts account)]
                              (if (error/anomaly? result)
                                (reduced result)
                                (conj acc result))))
                          []
                          accounts)]
        {:accounts enriched
         :before before
         :after after})))))

(defn get-account-by-bban
  [txn bban]
  (store/get-account-by-bban txn bban))

(defn find-accounts-by-party
  [txn bank-id party-id]
  (store/find-accounts-by-party txn bank-id party-id))
