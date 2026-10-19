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

(defn- handle-closure
  "Carries a close on once its balance has moved, or refuses it with the
  closure's reason."
  [config data]
  (case (:status-after data)
    :account-closure-status-paid (core/closure-paid (fdb config) data)
    :account-closure-status-refused (core/closure-refused (fdb config) data)
    nil))

(defn- dispatch
  [config message]
  (let [{:keys [event payload]} message
        {:keys [schemas]} config]
    (case event
      ("cash-account-status-changed" "cash-account-operation-changed")
      nil

      "account-closure-status-changed"
      (let-nom> [data (avro/deserialize-same (get schemas event) payload)]
        (handle-closure config data))

      (do (log/warnf "Unknown cash-account event: %s" event) nil))))

(defrecord CashAccountEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))

(def ^:private provider-events
  {"payment-account-opened" core/provider-opened
   "payment-account-open-refused" core/provider-refused
   "payment-account-closed" core/provider-closed
   "payment-address-reissued" core/provider-reissued
   "payment-account-close-refused" core/provider-close-refused
   "payment-address-reissue-failed" core/provider-reissue-failed
   "payment-account-operation-completed" core/provider-operation-completed
   "payment-account-operation-refused" core/provider-operation-refused})

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
