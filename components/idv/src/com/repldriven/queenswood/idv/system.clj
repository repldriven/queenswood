(ns com.repldriven.queenswood.idv.system
  (:require
    [com.repldriven.queenswood.idv.commands :as commands]
    [com.repldriven.queenswood.idv.domain :as domain]
    [com.repldriven.queenswood.idv.events :as events]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (commands/->IdvProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :bus nil
                   :idv-command-channel nil}
   :system/instance-schema some?})

(def ^:private event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (events/->IdvEventProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component}
   :system/instance-schema some?})

(def ^:private party-event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (events/->IdvPartyEventProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :bus nil
                   :idv-command-channel nil}
   :system/instance-schema some?})

(def ^:private criteria-check
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance
                       (let [{:keys [policy idv-provider]} config]
                         (let-nom> [_ (domain/check-criteria [policy]
                                                             idv-provider)]
                           idv-provider))))
   :system/config {:policy system/required-component :idv-provider nil}
   :system/instance-schema some?})

(system/defcomponents :idv
                      {:processor processor
                       :event-processor event-processor
                       :party-event-processor party-event-processor
                       :criteria-check criteria-check})
