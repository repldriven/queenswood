(ns com.repldriven.queenswood.api.cash-account.commands
  (:require
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.shared.actor :as shared.actor]

    [com.repldriven.queenswood.cash-account-api.interface :as
     cash-account-api]))

(defn- dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [cash-accounts]} dispatchers]
    cash-accounts))

(defn- send-command
  [request command data ordering-key]
  (let [response (commands/send (dispatcher request)
                                request
                                command
                                "cash-account"
                                data
                                {:ordering-key ordering-key})]
    (cond-> response
            (= 200 (:status response))
            (update :body cash-account-api/->body))))

(defn open-cash-account
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters]
    (commands/created (send-command request
                                    "open-cash-account"
                                    (assoc body
                                           :bank-id bank-id
                                           :actor (shared.actor/actor auth))
                                    bank-id)
                      #(str "/v1/cash-accounts/" (:account-id %)))))

(defn close-cash-account
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id]} path]
    (send-command request
                  "close-cash-account"
                  {:bank-id bank-id
                   :account-id account-id
                   :actor (shared.actor/actor auth)}
                  account-id)))

(defn suspend-cash-account
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id]} path]
    (send-command request
                  "suspend-cash-account"
                  {:bank-id bank-id
                   :account-id account-id
                   :actor (shared.actor/actor auth)}
                  account-id)))

(defn resume-cash-account
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id]} path]
    (send-command request
                  "resume-cash-account"
                  {:bank-id bank-id
                   :account-id account-id
                   :actor (shared.actor/actor auth)}
                  account-id)))

(defn rotate-cash-account-address
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path]} parameters
        {:keys [account-id]} path]
    (send-command request
                  "rotate-cash-account-address"
                  {:bank-id bank-id
                   :account-id account-id
                   :actor (shared.actor/actor auth)}
                  account-id)))
