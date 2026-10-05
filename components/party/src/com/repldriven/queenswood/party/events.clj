(ns com.repldriven.queenswood.party.events
  (:require
    [com.repldriven.queenswood.party.core :as core]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- handle-idv-status-changed
  [config data]
  (let [{:keys [bank-id party-id status-after]} data]
    (core/apply-idv-status config bank-id party-id status-after)))

(defn- dispatch
  [config message]
  (let [{:keys [event payload]} message]
    (when (= "idv-status-changed" event)
      (let-nom> [data (avro/deserialize-same (get (:schemas config) event)
                                             payload)]
        (handle-idv-status-changed config data)))))

(defrecord PartyIdvEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
