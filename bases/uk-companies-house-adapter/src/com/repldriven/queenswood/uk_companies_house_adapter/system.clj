(ns com.repldriven.queenswood.uk-companies-house-adapter.system
  (:require
    [com.repldriven.queenswood.uk-companies-house-adapter.commands :as commands]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private command-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance
                       (commands/->UkCompaniesHouseCommandProcessor config)))
   :system/config {:schemas system/required-component
                   :record-db system/required-component
                   :record-store system/required-component
                   :companies-house-url system/required-component
                   :breaker system/required-component}
   :system/config-schema [:map [:breaker circuit-breaker/breaker-schema]]
   :system/instance-schema some?})

(system/defcomponents :uk-companies-house-adapter
                      {:command-processor command-processor})
