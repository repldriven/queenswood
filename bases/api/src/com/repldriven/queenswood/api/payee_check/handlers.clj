(ns com.repldriven.queenswood.api.payee-check.handlers
  (:require
    [com.repldriven.queenswood.api.commands :as commands]))

(defn- dispatcher
  [request]
  (-> request
      :dispatchers
      :payee-checks))

(defn create-check
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters]
    (commands/created (commands/send (dispatcher request)
                                     request
                                     "check-payee"
                                     "payee-check"
                                     (assoc body :bank-id bank-id))
                      #(str "/v1/payee-checks/" (:check-id %)))))