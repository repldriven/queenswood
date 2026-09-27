(ns com.repldriven.queenswood.modulr-webhook.system
  (:require
    [com.repldriven.queenswood.modulr-webhook.signature :as signature]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private credentials
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance
                       (let [{:keys [key-id secret algorithm]} config]
                         (assoc (signature/credentials key-id secret)
                                :algorithm
                                (or algorithm "hmac-sha1")))))
   :system/config {:key-id nil :secret nil :algorithm nil}
   :system/instance-schema map?})

(system/defcomponents :modulr-webhook {:credentials credentials})
