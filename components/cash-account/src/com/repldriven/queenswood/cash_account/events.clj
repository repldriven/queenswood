(ns com.repldriven.queenswood.cash-account.events
  (:require
    [com.repldriven.queenswood.cash-account.core :as core]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- fdb
  [config]
  (select-keys config [:record-db :record-store]))

(defn- handle-status-changed
  "Close an account the provider never held as soon as it is closing:
  there is nothing for the provider to close, so no reply will complete
  it. An account the provider holds is closed by the provider's reply to
  the command its activity sends."
  [config data]
  (let [{:keys [bank-id account-id status-after]} data]
    (when (= :cash-account-status-closing status-after)
      (core/complete-status-transition (fdb config)
                                       bank-id
                                       account-id
                                       :cash-account-status-closing
                                       (fn [account]
                                         (nil? (:provider-account-id
                                                account)))))))

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
   "payment-address-reissued" core/provider-reissued
   "payment-account-close-refused" core/provider-close-refused
   "payment-address-reissue-failed" core/provider-reissue-failed})

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
