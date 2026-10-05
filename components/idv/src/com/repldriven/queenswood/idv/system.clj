(ns com.repldriven.queenswood.idv.system
  (:require
    [com.repldriven.queenswood.idv.commands :as commands]
    [com.repldriven.queenswood.idv.domain :as domain]
    [com.repldriven.queenswood.idv.events :as events]

    [com.repldriven.queenswood.idv-provider.interface :as idv-provider]

    [com.repldriven.mono.cache.interface :as cache]
    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private ten-minutes-ms 600000)

(def ^:private processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (commands/->IdvProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :bus nil
                   :idv-providers nil}
   :system/instance-schema some?})

(def ^:private event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance
                       (events/->IdvEventProcessor
                        (assoc config
                               :policy-cache
                               (cache/create (:policy-cache-ttl-ms config))))))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :policy-cache-ttl-ms ten-minutes-ms}
   :system/instance-schema some?})

(def ^:private party-event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (events/->IdvPartyEventProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component}
   :system/instance-schema some?})

(def ^:private activity-event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (events/->IdvActivityEventProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :bus system/required-component
                   :idv-providers system/required-component}
   :system/instance-schema some?})

(def ^:private criteria-check
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or instance
         (let [{:keys [policy idv-providers]} config]
           (let-nom> [_ (some (fn [{:keys [declaration]}]
                                (domain/check-criteria [policy] declaration))
                              (idv-provider/entries idv-providers))]
             idv-providers))))
   :system/config {:policy system/required-component
                   :idv-providers system/required-component}
   :system/instance-schema some?})

(system/defcomponents :idv
                      {:processor processor
                       :event-processor event-processor
                       :party-event-processor party-event-processor
                       :activity-event-processor activity-event-processor
                       :criteria-check criteria-check})
