(ns com.repldriven.queenswood.onfido-adapter.system
  (:require
    [com.repldriven.queenswood.onfido-adapter.commands :as commands]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]
    [com.repldriven.queenswood.onfido-webhook.interface :as onfido-webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private destination "adapter:onfido")

(defn- adapter-webhook-url
  [adapter-url]
  (str adapter-url onfido-webhook/path))

(defn- register-webhook
  [onfido-url adapter-url]
  (http/request
   {:method :post
    :url (str onfido-url "/v3.6/webhooks")
    :headers {"Content-Type" "application/json"}
    :body (json/write-str {:url (adapter-webhook-url adapter-url)
                           :events ["workflow_run.completed"]})}))

(defn- ok-response?
  [res]
  (and (map? res) (number? (:status res)) (< (:status res) 400)))

(defn- failed?
  [res]
  (let [status (:status res)]
    (or (error/anomaly? res)
        (not (number? status))
        (<= 500 status)
        (contains? #{408 429} status))))

(defn- held-urls
  [res]
  (when (ok-response? res)
    (let [body (error/try-nom :onfido-adapter/webhooks
                              "Onfido's webhook list is not JSON"
                              (json/read-str (:body res)))]
      (when-not (error/anomaly? body)
        (set (map (fn [w] (get w "url")) (get body "webhooks")))))))

(defn- ensure-registered
  "Register the adapter's webhook where Onfido does not hold its URL, so
  one a simulator forgot on restart is registered again, and mark the
  adapter ready once Onfido holds it. Returns `:held`, `:missing` where
  Onfido refused it, or `:failed` where it did not answer."
  [config]
  (let [{:keys [onfido-url adapter-url readiness]} config
        res (http/request {:method :get
                           :url (str onfido-url "/v3.6/webhooks")
                           :headers {"Accept" "application/json"}})]
    (if (failed? res)
      :failed
      (let [registration
            (when-not (contains? (held-urls res)
                                 (adapter-webhook-url adapter-url))
              (log/info "Registering the Onfido webhook"
                        {:adapter adapter-url})
              (register-webhook onfido-url adapter-url))]
        (cond
         (or (nil? registration) (ok-response? registration))
         (do (reset! readiness true) :held)

         (failed? registration)
         :failed

         :else
         :missing)))))

(def ^:private readiness
  {:system/start (fn [{:system/keys [instance]}] (or instance (atom false)))
   :system/instance-schema some?})

(def ^:private registrar
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or instance
         (circuit-breaker/start-probe
          config
          (get-in config [:delivery-policy :breaker])
          destination
          {:probe (fn [] (ensure-registered config))
           :outcome-of (fn [res] (if (= :failed res) :failed :answered))
           :interval-ms
           (fn [res]
             (if (= :held res) (:check-ms config) (:retry-ms config)))})))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:onfido-url system/required-component
                   :adapter-url system/required-component
                   :readiness system/required-component
                   :record-db system/required-component
                   :record-store system/required-component
                   :delivery-policy system/required-component
                   :retry-ms system/required-component
                   :check-ms system/required-component}
   :system/config-schema [:map
                          [:delivery-policy
                           circuit-breaker/delivery-policy-schema]
                          [:retry-ms pos-int?]
                          [:check-ms pos-int?]]
   :system/instance-schema map?})

(def ^:private command-processor
  {:system/start (fn [{:system/keys [config instance]}]
                   (or instance (commands/->OnfidoCommandProcessor config)))
   :system/config {:schemas system/required-component
                   :record-db system/required-component
                   :record-store system/required-component}
   :system/instance-schema some?})

(system/defcomponents :onfido-adapter
                      {:readiness readiness
                       :registrar registrar
                       :command-processor command-processor})
