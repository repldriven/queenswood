(ns com.repldriven.queenswood.access-log.system
  (:require
    [com.repldriven.queenswood.access-log.writer :as writer]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private writer
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (writer/start config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :capacity system/required-component
                   :batch-size system/required-component
                   :flush-ms system/required-component
                   :telemetry nil}
   :system/config-schema [:map
                          [:capacity pos-int?]
                          [:batch-size pos-int?]
                          [:flush-ms pos-int?]]
   :system/instance-schema map?})

(system/defcomponents :access-log {:writer writer})
