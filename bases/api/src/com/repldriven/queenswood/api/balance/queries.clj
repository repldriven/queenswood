(ns com.repldriven.queenswood.api.balance.queries
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.balance-query.interface :as balances]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn list-balances
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id]} path
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [_ (cash-accounts/get-account config
                                               bank-id
                                               account-id)
                  balances (balances/get-balances config bank-id account-id)]
                 balances)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn get-balance
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id balance-type currency balance-status]} path
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [_ (cash-accounts/get-account config
                                               bank-id
                                               account-id)
                  balance (balances/get-balance config
                                                bank-id
                                                account-id
                                                balance-type
                                                currency
                                                balance-status)]
                 balance)]
    (cond
     (error/anomaly? result)
     (errors/anomaly->response result)

     (nil? result)
     {:status 404
      :body (errors/error-response 404 "REJECTED"
                                   ":balance/not-found"
                                   "Balance not found")}

     :else
     {:status 200 :body result})))

