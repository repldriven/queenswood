(ns com.repldriven.queenswood.modulr-relay.system
  (:require
    [com.repldriven.queenswood.modulr-relay.outbound.core :as outbound]

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
                   :reconcile-after-ms system/required-component
                   :record-store system/required-component
                   :modulr-url system/required-component
                   :credentials system/required-component
                   :customer-id system/required-component
                   :schemas system/required-component
                   :post-fn nil}
   :system/config-schema (conj intent-poller/config-schema
                               [:reconcile-after-ms pos-int?])
   :system/instance-schema map?})

(system/defcomponents :modulr-relay {:outbound-runner outbound-runner})
