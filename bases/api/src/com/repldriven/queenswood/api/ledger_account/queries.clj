(ns com.repldriven.queenswood.api.ledger-account.queries
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.balance-query.interface :as balances]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]

    [clojure.set :as set]))

(defn- ->api
  "Present a stored LedgerAccount over the wire: its `code` role as its
  chart number, and the class and type the code determines."
  [account]
  (let [{:keys [code]} account]
    (assoc account
           :code (ledger-accounts/chart-number code)
           :account-class (ledger-accounts/account-class code)
           :account-type (ledger-accounts/account-type code))))

(defn- with-posted-balance
  "Attach the account's derived `:posted-balance` ({value, currency}),
  the same figure the balances endpoint derives, from the balances the
  chart scan paired it with."
  [{:keys [account balances]}]
  (assoc account
         :posted-balance
         (:posted-balance (balances/totals balances (:currency account)))))

(defn- trial-balance-entry
  "Project an enriched account into a bank-balance trial-balance entry:
  its currency, normal side (from its class), and posted net."
  [account]
  {:currency (:currency account)
   :normal-side (if (ledger-accounts/debit-normal? account)
                  :debit
                  :credit)
   :value (:value (:posted-balance account))})

(defn list-ledger-accounts
  [request]
  (let [{:keys [record-db record-store auth]} request
        {:keys [bank-id]} auth
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [pairs (ledger-accounts/list-accounts-with-balances config
                                                                     bank-id)
                  enriched (mapv with-posted-balance pairs)]
                 {:items (mapv ->api enriched)
                  :trial-balance (balances/trial-balance
                                  (map trial-balance-entry enriched))})]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn get-ledger-account
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [ledger-account-id]} path
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [account (ledger-accounts/get-account config
                                                       bank-id
                                                       ledger-account-id)
                  _ (when (nil? account)
                      (error/reject :ledger-account/not-found
                                    {:message "Ledger account not found"
                                     :ledger-account-id ledger-account-id}))]
                 (->api account))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn list-balances
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [ledger-account-id]} path
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [account (ledger-accounts/get-account config
                                                       bank-id
                                                       ledger-account-id)
                  _ (when (nil? account)
                      (error/reject :ledger-account/not-found
                                    {:message "Ledger account not found"
                                     :ledger-account-id ledger-account-id}))
                  found (ledger-accounts/get-balances config bank-id account)]
                 (set/rename-keys found {:balances :items}))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))