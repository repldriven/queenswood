(ns com.repldriven.queenswood.api.payment.commands
  (:require
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.payment.queries :as queries]
    [com.repldriven.queenswood.api.shared.actor :as actor]))

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
     (commands/read-back
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
      (fn [{:keys [bank-id payment-id]}]
        (queries/read-internal-payment request bank-id payment-id)))
     #(str "/v1/payments/internal/" (:payment-id %)))))

(defn submit-outbound-payment
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters]
    (commands/created
     (commands/read-back
      (commands/send (dispatcher request)
                     request
                     "submit-outbound-payment"
                     "payment-command-reply"
                     (assoc body
                            :bank-id bank-id
                            :actor (actor/actor auth))
                     {:ordering-key (:debtor-account-id body)})
      (fn [{:keys [bank-id payment-id]}]
        (queries/read-outbound-payment request bank-id payment-id)))
     #(str "/v1/payments/outbound/" (:payment-id %)))))
