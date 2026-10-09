(ns com.repldriven.queenswood.api.payee-check.handlers
  (:require
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.payee-check.queries :as queries]
    [com.repldriven.queenswood.api.shared.actor :as shared.actor]))

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
                                "payee-check"
                                (assoc body
                                       :bank-id bank-id
                                       :actor (shared.actor/actor auth)))]
    (commands/created (cond-> response
                              (= 200 (:status response))
                              (update :body queries/->body))
                      #(str "/v1/payee-checks/" (:check-id %)))))
