(ns com.repldriven.queenswood.idv-provider.system
  (:require
    [com.repldriven.queenswood.idv-provider.core :as core]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private declaration
  {:system/start (fn [{:system/keys [config instance]}] (or instance config))
   :system/config {}
   :system/instance-schema map?})

(def ^:private providers
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (core/providers config)))
   :system/config {:default system/required-component
                   :providers system/required-component}
   :system/instance-schema map?})

(system/defcomponents :idv-provider
                      {:declaration declaration :providers providers})
