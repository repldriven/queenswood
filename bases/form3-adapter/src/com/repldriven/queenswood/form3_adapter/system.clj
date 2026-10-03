(ns com.repldriven.queenswood.form3-adapter.system
  (:require
    [com.repldriven.queenswood.form3-adapter.commands :as commands]
    [com.repldriven.queenswood.form3-adapter.provider :as provider]
    [com.repldriven.queenswood.form3-adapter.webhook.routes :as webhook]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]
    [com.repldriven.queenswood.form3-relay.interface :as relay]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private destination "adapter:form3")

(def ^:private subscriptions-path "/v1/notification/subscriptions")

(defn- call
  [config request]
  (relay/classify (relay/request (select-keys config [:form3-url :credentials])
                                 request)))

(defn- callback-uri
  [config]
  (str (:webhook-url config) webhook/path))

(defn- subscribe
  [config [record-type event-type]]
  (call config
        {:method :post
         :path subscriptions-path
         :body {:data {:id (str (utility/uuidv7))
                       :type "subscriptions"
                       :attributes {:callback_transport "http"
                                    :callback_uri (callback-uri config)
                                    :record_type record-type
                                    :event_type event-type}}}}))

(defn- ensure-subscribed
  "Subscribe to each record and event type Form3 does not hold for this
  adapter's URL, so one a simulator forgot on restart is subscribed
  again, and mark the adapter ready once Form3 holds them all. Returns
  `:held`, `:missing` where Form3 refused one, or `:failed` where it did
  not answer."
  [config]
  (let [[outcome result] (call config {:method :get :path subscriptions-path})]
    (case outcome
      :ok
      (let [held (set (map (fn [{:keys [attributes]}]
                             [(:record_type attributes)
                              (:event_type attributes)
                              (:callback_uri attributes)])
                           (:data result)))
            absent (remove (fn [[record-type event-type]]
                             (contains? held
                                        [record-type event-type
                                         (callback-uri config)]))
                           webhook/subscriptions)
            outcomes (mapv (fn [subscription]
                             (log/info "Subscribing to a Form3 notification"
                                       {:subscription subscription})
                             (first (subscribe config subscription)))
                           absent)]
        (cond
         (every? #{:ok} outcomes)
         (do (reset! (:readiness config) true) :held)

         (some #{:retry} outcomes)
         :failed

         :else
         :missing))

      :retry
      :failed

      :missing)))

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
          {:probe (fn [] (ensure-subscribed config))
           :outcome-of (fn [res] (if (= :failed res) :failed :answered))
           :interval-ms
           (fn [res]
             (if (= :held res) (:check-ms config) (:retry-ms config)))})))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [stop]} instance] (stop)))
   :system/config {:form3-url system/required-component
                   :credentials system/required-component
                   :webhook-url system/required-component
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
                         (commands/->Form3CommandProcessor config))))
   :system/config {:schemas system/required-component
                   :record-db system/required-component
                   :record-store system/required-component
                   :payment-provider system/required-component}
   :system/instance-schema some?})

(system/defcomponents :form3-adapter
                      {:readiness readiness
                       :registrar registrar
                       :command-processor command-processor})
