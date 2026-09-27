(ns com.repldriven.queenswood.cash-account.events
  (:require
    [com.repldriven.queenswood.cash-account.core :as core]
    [com.repldriven.queenswood.cash-account.domain :as domain]

    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.cash-account-query.interface :as q]
    [com.repldriven.queenswood.party-query.interface :as parties]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.message-bus.interface :as message-bus]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- fdb
  [config]
  (select-keys config [:record-db :record-store]))

(defn- send-command
  [config command account-id data]
  (let [{:keys [bus schemas scheme-account-command-channel]} config]
    (let-nom> [payload (avro/serialize (get schemas command) data)]
      (message-bus/send bus
                        scheme-account-command-channel
                        {:command command
                         :id (str (utility/uuidv7))
                         :correlation-id (str (utility/uuidv7))
                         :causation-id account-id
                         :payload payload}))))

(defn- open-at-provider
  [config account]
  (let [{:keys [bank-id account-id party-id product-id version-id currency]}
        account]
    (let-nom>
      [version (products/get-version (fdb config) bank-id product-id version-id)
       schemes (domain/address-schemes version)
       party (parties/get-party (fdb config) bank-id party-id)]
      (send-command config
                    "open-payment-account"
                    account-id
                    {:bank-id bank-id
                     :account-id account-id
                     :holder-name (:display-name party)
                     :currency currency
                     :address-schemes schemes}))))

(defn- close-at-provider
  [config account]
  (let [{:keys [bank-id account-id provider-account-id]} account]
    (if provider-account-id
      (send-command config
                    "close-payment-account"
                    account-id
                    {:bank-id bank-id
                     :account-id account-id
                     :provider-account-id provider-account-id})
      (core/complete-status-transition (fdb config)
                                       bank-id
                                       account-id
                                       :cash-account-status-closing))))

(defn- reissue-at-provider
  [config account]
  (let [{:keys [bank-id account-id provider-account-id pending-rotation-key]}
        account]
    (send-command config
                  "reissue-payment-address"
                  account-id
                  (utility/assoc-some {:bank-id bank-id
                                       :account-id account-id
                                       :rotation-key pending-rotation-key}
                                      :provider-account-id
                                      provider-account-id))))

(defn- handle-status-changed
  [config data]
  (let [{:keys [bank-id account-id status-after change-kind]} data]
    (let-nom> [account (q/find-account (fdb config) bank-id account-id)]
      (let [{:keys [account-status pending-rotation-key]} account]
        (cond
         (nil? account)
         nil

         (and (= :cash-account-status-opening status-after)
              (= :cash-account-status-opening account-status))
         (open-at-provider config account)

         (and (= :cash-account-status-closing status-after)
              (= :cash-account-status-closing account-status))
         (close-at-provider config account)

         (and (= :cash-account-change-kind-rotate-requested change-kind)
              pending-rotation-key)
         (reissue-at-provider config account))))))

(defn- dispatch
  [config message]
  (let [{:keys [event payload]} message
        {:keys [schemas]} config
        schema (get schemas event)]
    (if-not schema
      (do (log/warnf "Unknown cash-account event: %s" event) nil)
      (let-nom> [data (avro/deserialize-same schema payload)]
        (case event
          "cash-account-status-changed" (handle-status-changed config data)
          (do (log/warnf "Unknown cash-account event: %s" event) nil))))))

(defrecord CashAccountEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))

(def ^:private provider-events
  {"payment-account-opened" core/provider-opened
   "payment-account-refused" core/provider-refused
   "payment-account-closed" core/provider-closed
   "payment-address-reissued" core/provider-reissued})

(defn- dispatch-provider-event
  [config message]
  (let [{:keys [event payload]} message
        {:keys [schemas]} config
        handle (get provider-events event)
        schema (get schemas event)]
    (if (or (nil? handle) (nil? schema))
      (error/fail :cash-account/unknown-event
                  {:message "Unknown payment account event" :event event})
      (let-nom> [data (avro/deserialize-same schema payload)]
        (handle (fdb config) data)))))

(defrecord PaymentAccountEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch-provider-event config message)))
