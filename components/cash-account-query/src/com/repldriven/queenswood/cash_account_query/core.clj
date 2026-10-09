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
  (let [{:keys [bank-id account-id currency]} account]
    (let-nom>
      [balances (when (:embed-balances opts)
                  (balances/get-balances txn bank-id account-id currency))
       transactions (when (:embed-transactions opts)
                      (transactions/get-transactions txn bank-id account-id))]
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

(defn get-accounts-by-id
  [txn bank-id account-ids]
  (let-nom>
    [found (store/find-accounts txn bank-id account-ids)]
    (reduce (fn [acc account-id]
              (if-some [account (get found account-id)]
                (conj acc account)
                (reduced (error/reject :cash-account/not-found
                                       {:message "Account not found"
                                        :bank-id bank-id
                                        :account-id account-id}))))
            []
            account-ids)))

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
               :account-product-type-sub-ledger-own-funds)
     version (or (first (filter #(= currency (:currency %))
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

(defn get-account-balances
  [txn bank-id account-id]
  (store/transact txn
                  (fn [txn]
                    (let-nom> [{:keys [currency]} (get-account txn
                                                               bank-id
                                                               account-id)]
                      (balances/get-balances txn bank-id account-id currency)))
                  :cash-account/get-balances
                  "Failed to read the account's balances"))

(defn get-account-balance
  [txn bank-id account-id balance-type balance-status]
  (store/transact txn
                  (fn [txn]
                    (let-nom> [_ (get-account txn bank-id account-id)]
                      (balances/get-balance txn
                                            bank-id
                                            account-id
                                            balance-type
                                            balance-status)))
                  :cash-account/get-balance
                  "Failed to read the account's balance"))

(defn page-account-transactions
  [txn bank-id account-id opts]
  (store/transact txn
                  (fn [txn]
                    (let-nom> [_ (get-account txn bank-id account-id)]
                      (transactions/page-transactions txn
                                                      bank-id
                                                      account-id
                                                      opts)))
                  :cash-account/page-transactions
                  "Failed to page the account's transactions"))
