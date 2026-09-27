(ns com.repldriven.queenswood.api.cash-account.commands
  (:require
    [com.repldriven.queenswood.api.commands :as commands]))

(defn- dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [cash-accounts]} dispatchers]
    cash-accounts))

(defn open-cash-account
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters]
    (commands/created (commands/send (dispatcher request)
                                     request
                                     "open-cash-account"
                                     "cash-account"
                                     (assoc body :bank-id bank-id))
                      #(str "/v1/cash-accounts/" (:account-id %)))))

(defn close-cash-account
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id]} path]
    (commands/send (dispatcher request)
                   request
                   "close-cash-account"
                   "cash-account"
                   {:bank-id bank-id
                    :account-id account-id})))

(defn suspend-cash-account
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id]} path]
    (commands/send (dispatcher request)
                   request
                   "suspend-cash-account"
                   "cash-account"
                   {:bank-id bank-id
                    :account-id account-id})))

(defn resume-cash-account
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id]} path]
    (commands/send (dispatcher request)
                   request
                   "resume-cash-account"
                   "cash-account"
                   {:bank-id bank-id
                    :account-id account-id})))

(defn rotate-cash-account-address
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id]} path]
    (commands/send (dispatcher request)
                   request
                   "rotate-cash-account-address"
                   "cash-account"
                   {:bank-id bank-id
                    :account-id account-id})))
