(ns com.repldriven.queenswood.reward.events
  (:require
    [com.repldriven.queenswood.reward.core :as core]
    [com.repldriven.queenswood.reward.domain :as domain]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.processor.interface :as processor]))

(defn- fdb
  [config]
  (select-keys config [:record-db :record-store :cache :caches]))

(defn- dispatch
  "Pays the opening reward when an account becomes opened. Every other
  cash-account change, and every other event on the channel, is
  nothing to a reward."
  [config message]
  (let [{:keys [event payload]} message]
    (when (= "cash-account-status-changed" event)
      (let-nom> [data (avro/deserialize-same (get (:schemas config) event)
                                             payload)]
        (when (domain/opening? data)
          (core/pay-opening (fdb config) data))))))

(defrecord RewardEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
