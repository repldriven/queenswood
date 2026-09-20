(ns com.repldriven.queenswood.demo-digital-bank.system
  (:require
    [com.repldriven.queenswood.demo-digital-bank.platform :as platform]
    [com.repldriven.queenswood.demo-digital-bank.streams :as streams]

    [com.repldriven.mono.system.interface :as system]))

(def ^:private default-session-ttl-seconds 86400)

(def ^:private default-keep-alive-ms
  "How long a stream waits for a notification before it says nothing,
  so a proxy between the app and the bank does not close it as idle."
  15000)

;; Instance-is-config, as mono's `jdbc/datasource` does. `:datasource` is
;; wired to `migrator.migrations` rather than to `jdbc.datasource`: the
;; migrator's instance is the datasource it migrated, and that reference
;; is what orders schema creation before the first query.
(def ^:private store
  {:system/start (fn [{:system/keys [config instance]}] (or instance config))
   :system/config {:datasource system/required-component}
   :system/config-schema [:map [:datasource some?]]
   :system/instance-schema map?})

(def ^:private platform
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (platform/client config)))
   :system/config {:url system/required-component
                   :client-id system/required-component
                   :client-secret system/required-component
                   :status "test"}
   :system/config-schema [:map [:url string?] [:client-id string?]
                          [:client-secret string?] [:status string?]]
   :system/instance-schema map?})

(def ^:private bank
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or instance
         (-> config
             (update :session-ttl-seconds
                     (fn [ttl] (or ttl default-session-ttl-seconds)))
             (update :keep-alive-ms (fn [ms] (or ms default-keep-alive-ms)))
             (assoc :streams (streams/registry)))))
   :system/stop (fn [{:system/keys [instance]}]
                  (some-> (:streams instance)
                          streams/close!))
   :system/config {:store system/required-component
                   :platform system/required-component
                   :sign-up-code system/required-component
                   :session-ttl-seconds nil
                   :keep-alive-ms nil
                   :webhook-secret nil}
   :system/config-schema [:map [:store some?] [:platform some?]
                          [:sign-up-code string?]
                          [:webhook-secret {:optional true} [:maybe string?]]]
   :system/instance-schema map?})

(system/defcomponents :demo-digital-bank
                      {:store store :platform platform :bank bank})
