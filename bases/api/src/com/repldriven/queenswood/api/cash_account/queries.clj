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
        {:keys [page embed]} (:query parameters)
        {embed-balances :balances embed-transactions :transactions} embed
        opts (utility/assoc-some (cursor/page-opts page)
                                 :embed-balances embed-balances
                                 :embed-transactions embed-transactions)
        result (cash-accounts/get-accounts
                {:record-db record-db :record-store record-store}
                bank-id
                opts)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      ;; Skip any account whose product-type reads back unset — proto2
      ;; deserialises an absent enum as `:product-type-unknown`.
      (let [customer-accounts (into []
                                    (comp (filter (fn [a]
                                                    (let [pt (:product-type a)]
                                                      (and
                                                       (some? pt)
                                                       (not=
                                                        :product-type-unknown
                                                        pt)))))
                                          (map cash-account-api/->body))
                                    (:accounts result))]
        {:status 200
         :body (cursor/page-body (cursor/request-path request)
                                 page
                                 customer-accounts
                                 result)}))))

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
        {:keys [path query]} parameters
        {:keys [account-id]} path
        {:keys [page]} query
        config {:record-db record-db :record-store record-store}
        result (error/let-nom>
                 [_ (cash-accounts/get-account config bank-id account-id)
                  found (transactions/page-transactions config
                                                        account-id
                                                        (cursor/page-opts
                                                         page))]
                 (cursor/page-body (cursor/request-path request)
                                   page
                                   (:transactions found)
                                   found))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))
