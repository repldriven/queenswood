(ns com.repldriven.queenswood.email.system
  (:require
    [com.repldriven.queenswood.email.events :as events]
    [com.repldriven.queenswood.email.outbound :as outbound]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private outbound-runner
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (outbound/start-runner config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component
                   :dispatcher system/required-component
                   :smtp system/required-component
                   :console-url system/required-component
                   :runner-id nil
                   :batch-size nil
                   :poll-ms nil}
   :system/instance-schema map?})

(def ^:private event-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (events/->EmailEventProcessor config)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :schemas system/required-component}
   :system/instance-schema some?})

(system/defcomponents :email
                      {:event-processor event-processor
                       :outbound-runner outbound-runner})
