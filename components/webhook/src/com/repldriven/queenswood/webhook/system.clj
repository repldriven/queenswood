(ns com.repldriven.queenswood.webhook.system
  (:require
    [com.repldriven.queenswood.webhook.events :as events]
    [com.repldriven.queenswood.webhook.outbound :as outbound]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private outbound-runner
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (outbound/start-runner config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :platform-hosts nil
                   :address-rule nil
                   :address-check nil
                   :runner-id nil
                   :delivery-policy system/required-component
                   :poll-ms system/required-component
                   :batch-size system/required-component
                   :claim-lease-ms system/required-component
                   :request-timeout-ms system/required-component
                   :max-in-flight-per-endpoint system/required-component}
   :system/config-schema [:map
                          [:delivery-policy
                           circuit-breaker/delivery-policy-schema]
                          [:poll-ms pos-int?]
                          [:batch-size pos-int?]
                          [:claim-lease-ms pos-int?]
                          [:request-timeout-ms pos-int?]
                          [:max-in-flight-per-endpoint pos-int?]]
   :system/instance-schema map?})

(def ^:private event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (events/->WebhookEventProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component}
   :system/instance-schema some?})

(system/defcomponents :webhook
                      {:event-processor event-processor
                       :outbound-runner outbound-runner})
