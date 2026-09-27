(ns com.repldriven.queenswood.modulr-relay.system
  (:require
    [com.repldriven.queenswood.modulr-relay.outbound :as outbound]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private outbound-runner
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (outbound/start-runner config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :modulr-url system/required-component
                   :credentials system/required-component
                   :customer-id system/required-component
                   :schemas system/required-component
                   :max-attempts nil
                   :initial-backoff-ms nil
                   :max-backoff-ms nil
                   :reconcile-after-ms nil
                   :post-fn nil
                   :poll-ms nil}
   :system/instance-schema map?})

(system/defcomponents :modulr-relay {:outbound-runner outbound-runner})
