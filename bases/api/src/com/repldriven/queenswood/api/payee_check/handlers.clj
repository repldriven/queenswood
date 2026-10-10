(ns com.repldriven.queenswood.api.payee-check.handlers
  (:require
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.payee-check.queries :as queries]
    [com.repldriven.queenswood.api.shared.actor :as actor]

    [com.repldriven.queenswood.schema.interface :as schema]))

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
    (commands/created (commands/reply-body
                       response
                       (fn [{:keys [record]}]
                         (queries/->body (schema/pb->PayeeCheck record))))
                      #(str "/v1/payee-checks/" (:check-id %)))))
