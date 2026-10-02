(ns com.repldriven.queenswood.onfido-relay.system
  (:require
    [com.repldriven.queenswood.onfido-relay.outbound :as outbound]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private outbound-runner
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (outbound/start-runner config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :onfido-url system/required-component
                   :api-token system/required-component
                   :workflows system/required-component
                   :idv-provider system/required-component
                   :hand-off-ttl-ms nil
                   :max-attempts nil
                   :initial-backoff-ms nil
                   :max-backoff-ms nil
                   :poll-ms nil}
   :system/instance-schema map?})

(system/defcomponents :onfido-relay {:outbound-runner outbound-runner})
