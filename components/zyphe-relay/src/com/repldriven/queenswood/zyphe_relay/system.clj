(ns com.repldriven.queenswood.zyphe-relay.system
  (:require
    [com.repldriven.queenswood.zyphe-relay.outbound :as outbound]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private outbound-runner
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (outbound/start-runner config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :zyphe-url system/required-component
                   :api-key system/required-component
                   :flow-id system/required-component
                   :sandbox system/required-component
                   :adapter-url system/required-component
                   :webhook-secret system/required-component
                   :max-attempts nil
                   :poll-ms nil}
   :system/instance-schema map?})

(system/defcomponents :zyphe-relay {:outbound-runner outbound-runner})
