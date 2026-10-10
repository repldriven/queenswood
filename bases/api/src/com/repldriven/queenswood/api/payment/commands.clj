(ns com.repldriven.queenswood.api.payment.commands
  (:require
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.shared.actor :as actor]

    [com.repldriven.queenswood.payment-api.interface :as coercion]
    [com.repldriven.queenswood.schema.interface :as schema]))

(defn- dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [payments]} dispatchers]
    payments))

(defn submit-internal-payment
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters]
    (commands/created
     (commands/reply-body
      (commands/send (dispatcher request)
                     request
                     "submit-internal-payment"
                     "payment-command-reply"
                     (assoc body
                            :bank-id bank-id
                            :actor (actor/actor auth))
                     ;; Serialise a debtor account's payments: they contend
                     ;; on its available balance, and that is the limit
                     ;; that can actually reject.
                     {:ordering-key (:debtor-account-id body)})
      (fn [{:keys [record]}]
        (coercion/->internal-body (schema/pb->InternalPayment record))))
     #(str "/v1/payments/internal/" (:payment-id %)))))

(defn- outbound-reply
  "The submit's reply, the payment it committed as the payment routes
  return a payment."
  [response]
  (commands/reply-body response
                       (fn [{:keys [record]}]
                         (coercion/->outbound-body (schema/pb->OutboundPayment
                                                    record)))))

(defn submit-outbound-payment
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters]
    (commands/created
     (outbound-reply
      (commands/send (dispatcher request)
                     request
                     "submit-outbound-payment"
                     "payment-command-reply"
                     (assoc body
                            :bank-id bank-id
                            :actor (actor/actor auth))
                     {:ordering-key (:debtor-account-id body)}))
     #(str "/v1/payments/outbound/" (:payment-id %)))))
