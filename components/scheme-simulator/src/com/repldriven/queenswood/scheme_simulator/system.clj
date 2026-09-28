(ns com.repldriven.queenswood.scheme-simulator.system
  (:require
    [com.repldriven.queenswood.scheme-simulator.core :as core]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private scheme
  {:system/start (fn [{:system/keys [instance]}] (or instance (core/scheme)))
   :system/config {}
   :system/instance-schema some?})

(def ^:private member
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance
                       (let [{:keys [scheme sort-code url]} config]
                         (core/join scheme sort-code url))))
   :system/stop (fn [{:system/keys [instance]}]
                  (when instance (core/leave instance)))
   :system/config {:scheme system/required-component
                   :sort-code system/required-component
                   :url system/required-component}
   :system/instance-schema map?})

(system/defcomponents :scheme-simulator {:scheme scheme :member member})
