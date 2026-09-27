(ns com.repldriven.queenswood.clearbank-webhook.system
  (:require
    [com.repldriven.queenswood.clearbank-webhook.signature :as signature]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private key-pair
  {:system/start (fn [{:system/keys [instance]}]
                   (or instance (signature/key-pair)))
   :system/instance-schema map?})

(system/defcomponents :clearbank-webhook {:key-pair key-pair})
