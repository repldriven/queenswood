(ns com.repldriven.queenswood.cash-account-query.core
  (:require
    [com.repldriven.queenswood.cash-account-query.store :as store]

    [com.repldriven.queenswood.balance-query.interface :as balances]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
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

(defn house-account
  [txn bank-id currency]
  (let-nom>
    [versions (products/find-products-by-type
               txn
               bank-id
               :product-type-sub-ledger-own-funds)
     version (or (first (filter #(some #{currency} (:allowed-currencies %))
                                versions))
                 (error/reject :cash-account/house-account-not-found
                               {:message
                                "The bank holds no own funds in this currency"
                                :bank-id bank-id
                                :currency currency}))
     accounts (store/find-accounts-by-product txn bank-id (:product-id version))
     account (or (first accounts)
                 (error/reject :cash-account/house-account-not-found
                               {:message
                                "The bank's own-funds product has no account"
                                :bank-id bank-id
                                :currency currency
                                :product-id (:product-id version)}))]
    account))
