(ns com.repldriven.queenswood.onfido-relay.system
  (:require
    [com.repldriven.queenswood.onfido-relay.outbound :as outbound]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private outbound-runner
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (outbound/start-runner config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:record-db system/required-component
                   :delivery-policy system/required-component
                   :poll-ms system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :onfido-url system/required-component
                   :api-token system/required-component
                   :workflows system/required-component
                   :idv-provider system/required-component
                   :hand-off-ttl-ms nil}
   :system/config-schema intent-poller/config-schema
   :system/instance-schema map?})

(system/defcomponents :onfido-relay {:outbound-runner outbound-runner})
