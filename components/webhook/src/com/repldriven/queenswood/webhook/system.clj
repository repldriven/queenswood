(ns com.repldriven.queenswood.webhook.system
  (:require
    [com.repldriven.queenswood.webhook.outbound :as outbound]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private outbound-runner
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (outbound/start-runner config)))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:record-db system/required-component
                   :record-store system/required-component
                   :platform-hosts nil
                   :address-check nil
                   :runner-id nil
                   :batch-size nil
                   :poll-ms nil}
   :system/instance-schema map?})

(system/defcomponents :webhook {:outbound-runner outbound-runner})
