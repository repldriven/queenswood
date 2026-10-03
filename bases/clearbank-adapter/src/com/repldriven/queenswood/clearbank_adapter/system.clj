(ns com.repldriven.queenswood.clearbank-adapter.system
  (:require
    [com.repldriven.queenswood.clearbank-adapter.commands
     :as commands]
    [com.repldriven.queenswood.clearbank-adapter.provider :as provider]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private destination "adapter:clearbank")

(defn- expected-url
  [webhook-url {:keys [path]}]
  (str webhook-url path))

(defn- register-webhook
  [simulator-url webhook-url {:keys [type path]}]
  (let [url (str webhook-url path)]
    (http/request
     {:method :post
      :url (str simulator-url "/v1/webhooks")
      :headers {"Content-Type" "application/json"}
      :body (json/write-str {:type type :url url})})))

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

(defn- held-webhooks
  [res]
  (when (ok-response? res)
    (let [body (error/try-nom :clearbank-adapter/webhooks
                              "ClearBank's webhook list is not JSON"
                              (json/read-str (:body res)))]
      (when-not (error/anomaly? body) (get body "webhooks")))))

(defn- ensure-registered
  "Register each webhook ClearBank does not hold for this adapter's URL,
  so one a simulator forgot on restart is registered again, and mark the
  adapter ready once ClearBank holds them all. Returns `:held`,
  `:missing` where ClearBank refused one, or `:failed` where it did not
  answer."
  [config]
  (let [{:keys [simulator-url webhook-url webhooks readiness]} config
        res (http/request {:method :get
                           :url (str simulator-url "/v1/webhooks")
                           :headers {"Accept" "application/json"}})]
    (if (failed? res)
      :failed
      (let [held (held-webhooks res)
            present? (fn [{:keys [type] :as webhook}]
                       (some (fn [w]
                               (and (= type (get w "type"))
                                    (= (expected-url webhook-url webhook)
                                       (get w "url"))))
                             held))
            results (mapv
                     (fn [webhook]
                       (log/info "Registering a ClearBank webhook"
                                 {:type (:type webhook)})
                       (register-webhook simulator-url webhook-url webhook))
                     (remove present? webhooks))]
        (cond
         (every? ok-response? results)
         (do (reset! readiness true) :held)

         (some failed? results)
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
   :system/config {:simulator-url system/required-component
                   :webhook-url system/required-component
                   :webhooks nil
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
                   (or instance
                       (let-nom> [_ (provider/check (:payment-provider config))]
                         (commands/->ClearBankCommandProcessor config))))
   :system/config {:schemas system/required-component
                   :record-db system/required-component
                   :record-store system/required-component
                   :payment-provider system/required-component
                   :sort-code system/required-component}
   :system/instance-schema some?})

(system/defcomponents :clearbank-adapter
                      {:readiness readiness
                       :registrar registrar
                       :command-processor command-processor})
