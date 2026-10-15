(ns com.repldriven.queenswood.payment.events.activity
  (:require
    [com.repldriven.queenswood.payment.domain.inbound :as inbound]
    [com.repldriven.queenswood.payment.events.provider-transfer :as
     provider-transfer]
    [com.repldriven.queenswood.payment.provider :as provider]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(defn- open-account
  [config data]
  (let [{:keys [bank-id account-id]} data]
    (provider/send-command config
                           bank-id
                           "open-payment-account"
                           account-id
                           (select-keys data
                                        [:bank-id :account-id :holder-name
                                         :currency :address-schemes]))))

(def ^:private operation-commands
  {:cash-account-operation-kind-suspend "block-payment-account"
   :cash-account-operation-kind-resume "unblock-payment-account"
   :cash-account-operation-kind-close "close-payment-account"
   :cash-account-operation-kind-rotate-address "reissue-payment-address"})

(defn- operate-account
  [config data]
  (let [{:keys [bank-id account-id kind]} data]
    (provider/send-command config
                           bank-id
                           (get operation-commands kind)
                           account-id
                           (select-keys data
                                        [:bank-id :account-id :operation-id
                                         :provider-account-id]))))

(defn- submit-payment
  [config data]
  (let [{:keys [bank-id payment-id]} data]
    (provider/send-command config
                           bank-id
                           "submit-payment"
                           payment-id
                           (-> data
                               (dissoc :bank-id)
                               (assoc :end-to-end-id payment-id)))))

(defn- return-payment
  [config data]
  (let [{:keys [bank-id payment-id]} data]
    (let-nom> [declaration (provider/declaration config config bank-id)]
      (when (inbound/returns-inbound? declaration)
        (provider/send-command config
                               bank-id
                               "return-payment"
                               payment-id
                               (dissoc data :bank-id :creditor-account-id))))))

(defn- simulate-payment
  [config data]
  (let [{:keys [bank-id simulation-id]} data]
    (provider/send-command config
                           bank-id
                           "simulate-provider-payment"
                           simulation-id
                           data)))

(def ^:private handlers
  {"cash-account-open-requested" open-account
   "cash-account-operation-requested" operate-account
   "transaction-posted" provider-transfer/mirror-posted
   "outbound-payment-submitted" submit-payment
   "inbound-payment-suspended" return-payment
   "inbound-payment-simulation-requested" simulate-payment})

(defn handle
  "Send the bank's payment provider what an activity entry asks of it,
  or nothing where the entry is not one a payment provider acts on."
  [config message]
  (let [{:keys [event payload]} message
        handler (get handlers event)]
    (when handler
      (let-nom> [data (avro/deserialize-same (get (:schemas config) event)
                                             payload)]
        (handler config data)))))
