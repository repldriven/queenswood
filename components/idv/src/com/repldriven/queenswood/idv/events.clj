(ns com.repldriven.queenswood.idv.events
  (:require
    [com.repldriven.queenswood.idv.activity :as activity]
    [com.repldriven.queenswood.idv.core :as core]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- dispatch
  [config message]
  (let [{:keys [event payload]} message
        {:keys [schemas]} config
        schema (get schemas event)]
    (if-not schema
      (do (log/warnf "Unknown IDV event: %s" event) nil)
      (let-nom> [data (avro/deserialize-same schema payload)]
        (case event
          "idv-evidence" (core/apply-evidence config data)
          "idv-session-opened" (core/record-hand-off config data)
          (do (log/warnf "Unknown IDV event: %s" event) nil))))))

(defrecord IdvEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))

(defn- handle-party-status-changed
  [config data]
  (let [{:keys [bank-id party-id status-after]} data]
    (when (= :party-status-pending status-after)
      (core/initiate-for-party config bank-id party-id))))

(defn- dispatch-party
  [config message]
  (let [{:keys [event payload]} message
        {:keys [schemas]} config
        schema (get schemas event)]
    (if-not schema
      (do (log/warnf "Unknown parties event: %s" event) nil)
      (let-nom> [data (avro/deserialize-same schema payload)]
        (case event
          "party-status-changed" (handle-party-status-changed config data)
          (do (log/warnf "Unknown parties event: %s" event) nil))))))

(defrecord IdvPartyEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch-party config message)))

(defrecord IdvActivityEventProcessor [config]
  processor/Processor
    (process [_ message] (activity/handle config message)))
