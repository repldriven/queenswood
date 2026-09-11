(ns com.repldriven.queenswood.api.cash-account.queries
  (:require
    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.cash-account-api.interface :as cash-account-api]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(defn list-cash-accounts
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [query]} parameters
        {:keys [page embed]} query
        {:keys [after before size]} page
        {embed-balances :balances embed-transactions :transactions} embed
        after-id (cursor/decode after)
        before-id (cursor/decode before)
        size (cursor/clamp-size size)
        opts (utility/assoc-some {:limit size}
                                 :after after-id
                                 :before before-id
                                 :embed-balances embed-balances
                                 :embed-transactions embed-transactions)
        result (cash-accounts/get-accounts
                {:record-db record-db
                 :record-store record-store}
                bank-id
                opts)]

    (if (error/anomaly? result)
      (errors/anomaly->response result)
      ;; Skip any account whose product-type reads back unset — proto2
      ;; deserialises an absent enum as `:product-type-unknown`.
      (let [{:keys [accounts before after]} result
            customer-accounts (mapv
                               cash-account-api/->body
                               (filterv
                                (fn [a]
                                  (let [pt (:product-type a)]
                                    (and (some? pt)
                                         (not= :product-type-unknown pt))))
                                accounts))
            links (when (seq customer-accounts)
                    (cursor/build-links "/v1/cash-accounts"
                                        size
                                        (when after-id before)
                                        after))]
        {:status 200
         :body (utility/assoc-seq {:cash-accounts customer-accounts}
                                  :links
                                  links)}))))

(defn get-cash-account
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path query]} parameters
        {:keys [account-id]} path
        {:keys [embed]} query
        {embed-balances :balances embed-transactions :transactions} embed
        result (cash-accounts/get-account
                {:record-db record-db :record-store record-store}
                bank-id
                account-id
                (utility/assoc-some {}
                                    :embed-balances embed-balances
                                    :embed-transactions embed-transactions))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body (cash-account-api/->body result)})))

(defn list-transactions
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id]} path
        config {:record-db record-db :record-store record-store}
        result (error/let-nom>
                 [_ (cash-accounts/get-account config
                                               bank-id
                                               account-id)
                  txns (transactions/get-transactions config account-id)]
                 txns)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body {:transactions (or result [])}})))
