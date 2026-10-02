(ns com.repldriven.queenswood.payment.events.activity
  (:require
    [com.repldriven.queenswood.payment.domain.inbound :as inbound]
    [com.repldriven.queenswood.payment.events.provider-transfer :as
     provider-transfer]
    [com.repldriven.queenswood.payment.provider :as provider]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

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

(defn- close-account
  [config data]
  (let [{:keys [bank-id account-id]} data]
    (provider/send-command config
                           bank-id
                           "close-payment-account"
                           account-id
                           (select-keys data
                                        [:bank-id :account-id
                                         :provider-account-id]))))

(defn- reissue-address
  [config data]
  (let [{:keys [bank-id account-id provider-account-id]} data]
    (provider/send-command config
                           bank-id
                           "reissue-payment-address"
                           account-id
                           (utility/assoc-some
                            (select-keys data
                                         [:bank-id :account-id :rotation-key])
                            :provider-account-id
                            provider-account-id))))

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

(def ^:private handlers
  {"account-opening" open-account
   "account-closing" close-account
   "account-address-rotation-requested" reissue-address
   "transaction-posted" provider-transfer/mirror-posted
   "outbound-payment-submitted" submit-payment
   "inbound-payment-suspended" return-payment})

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
