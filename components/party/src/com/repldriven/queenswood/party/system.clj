(ns com.repldriven.queenswood.party.system
  (:require
    [com.repldriven.queenswood.party.commands :as commands]
    [com.repldriven.queenswood.party.events :as events]

    [com.repldriven.mono.cache.interface :as cache]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private ten-minutes-ms 600000)

(def ^:private processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance
                       (commands/->PartyProcessor
                        (assoc config
                               :policy-cache
                               (cache/create (:policy-cache-ttl-ms config))))))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :policy-cache-ttl-ms ten-minutes-ms}
   :system/instance-schema some?})

(def ^:private idv-event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (events/->PartyIdvEventProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component}
   :system/instance-schema some?})

(system/defcomponents :party
                      {:processor processor
                       :idv-event-processor idv-event-processor})
