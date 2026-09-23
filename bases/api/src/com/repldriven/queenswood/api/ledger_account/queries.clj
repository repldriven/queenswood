(ns com.repldriven.queenswood.api.ledger-account.queries
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.balance-query.interface :as balances]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]

    [clojure.set :as set]))

(defn- ->api
  "Present a stored LedgerAccount over the wire: the internal
  `:ledger-account-id` is exposed as `:account-id` so the resource speaks
  the same id key as the path parameter and the balance API, and the
  `:gl-account-code` role is rendered back to its chart number as the
  `:gl-code` string clients (and the console's ledger view) expect."
  [account]
  (-> account
      (set/rename-keys {:ledger-account-id :account-id})
      (assoc :gl-code
             (ledger-accounts/gl-account-code->gl-code
              (:gl-account-code account)))
      (dissoc :gl-account-code)))

(defn- with-posted-balance
  "Attach the account's derived `:posted-balance` ({value, currency}),
  the same figure the balances endpoint derives, from the balances the
  chart scan paired it with."
  [{:keys [account balances]}]
  (assoc account :posted-balance (:posted-balance (balances/totals balances))))

(defn- trial-balance-entry
  "Project an enriched account into a bank-balance trial-balance entry:
  its currency, normal side (from the gl-account-type), and posted net."
  [account]
  {:currency (:currency account)
   :normal-side (if (ledger-accounts/debit-normal? (:gl-account-type account))
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
                 {:ledger-accounts (mapv ->api enriched)
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
        {:keys [account-id]} path
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [account (ledger-accounts/get-account config
                                                       bank-id
                                                       account-id)
                  _ (when (nil? account)
                      (error/reject :ledger-account/not-found
                                    {:message "Ledger account not found"
                                     :account-id account-id}))]
                 (->api account))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn list-balances
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id]} path
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [account (ledger-accounts/get-account config
                                                       bank-id
                                                       account-id)
                  _ (when (nil? account)
                      (error/reject :ledger-account/not-found
                                    {:message "Ledger account not found"
                                     :account-id account-id}))
                  balances (balances/get-balances config bank-id account-id)]
                 balances)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))