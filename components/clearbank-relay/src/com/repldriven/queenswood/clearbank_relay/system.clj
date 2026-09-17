(ns com.repldriven.queenswood.clearbank-relay.system
  (:require
    [com.repldriven.queenswood.clearbank-relay.outbound :as outbound]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private outbound-runner
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (outbound/start-runner config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :clearbank-url system/required-component
                   :schemas system/required-component
                   :max-attempts nil
                   :initial-backoff-ms nil
                   :max-backoff-ms nil
                   :post-fn nil
                   :poll-ms nil}
   :system/instance-schema map?})

(system/defcomponents :clearbank-relay {:outbound-runner outbound-runner})
