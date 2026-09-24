(ns com.repldriven.queenswood.api.payment.commands
  (:require
    [com.repldriven.queenswood.api.commands :as commands]

    [com.repldriven.queenswood.payment-api.interface :as coercion]))

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
     (commands/send (dispatcher request)
                    request
                    "submit-internal-payment"
                    "internal-payment"
                    (assoc body :bank-id bank-id)
                    ;; Serialise a debtor account's payments: they contend
                    ;; on its available balance, and that is the limit
                    ;; that can actually reject.
                    {:ordering-key (:debtor-account-id body)})
     #(str "/v1/payments/internal/" (:payment-id %)))))

(defn submit-outbound-payment
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [body]} parameters]
    (commands/created
     (commands/send (dispatcher request)
                    request
                    "submit-outbound-payment"
                    "outbound-payment"
                    (-> body
                        (update :scheme coercion/encode-payment-scheme)
                        (assoc :bank-id bank-id))
                    {:ordering-key (:debtor-account-id body)})
     #(str "/v1/payments/outbound/" (:payment-id %)))))
