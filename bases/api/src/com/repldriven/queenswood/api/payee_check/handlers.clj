(ns com.repldriven.queenswood.api.payee-check.handlers
  (:require
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.payee-check.queries :as queries]
    [com.repldriven.queenswood.api.shared.actor :as actor]))

(defn- dispatcher
  [request]
  (-> request
      :dispatchers
      :payee-checks))

(defn create-check
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters
        response (commands/send (dispatcher request)
                                request
                                "check-payee"
                                "payee-check-command-reply"
                                (assoc body
                                       :bank-id bank-id
                                       :actor (actor/actor auth)))]
    (commands/created (commands/read-back
                       response
                       (fn [{:keys [bank-id check-id]}]
                         (queries/read-check request bank-id check-id)))
                      #(str "/v1/payee-checks/" (:check-id %)))))
