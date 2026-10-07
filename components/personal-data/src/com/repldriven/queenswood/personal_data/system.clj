(ns com.repldriven.queenswood.personal-data.system
  (:require
    [com.repldriven.queenswood.personal-data.core :as core]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private clearance
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (core/clear config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component}
   :system/instance-schema some?})

(system/defcomponents :personal-data {:clearance clearance})
