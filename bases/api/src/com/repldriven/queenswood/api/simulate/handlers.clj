(ns com.repldriven.queenswood.api.simulate.handlers
  (:require
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.shared.actor :as actor]))

(defn- dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [payments]} dispatchers]
    payments))

(defn inbound-transfer
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [body]} parameters
        {:keys [bank-id]} auth
        response (commands/send (dispatcher request)
                                request
                                "simulate-inbound-payment"
                                "simulate-inbound-payment-reply"
                                (assoc body
                                       :bank-id bank-id
                                       :actor (actor/actor auth)))]
    (cond-> response
            (= 200 (:status response))
            (assoc :status 202))))
