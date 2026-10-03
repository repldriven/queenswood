(ns com.repldriven.queenswood.modulr-adapter.system
  (:require
    [com.repldriven.queenswood.modulr-adapter.commands :as commands]
    [com.repldriven.queenswood.modulr-adapter.provider :as provider]
    [com.repldriven.queenswood.modulr-adapter.webhook.routes :as webhook]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]
    [com.repldriven.queenswood.modulr-relay.interface :as relay]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private destination "adapter:modulr")

(defn- call
  [config request]
  (relay/classify (relay/request (select-keys config [:modulr-url :credentials])
                                 request)))

(defn- subscriptions-path
  [{:keys [customer-id]}]
  (str "/customers/" customer-id "/integration-notifications"))

(defn- subscribe
  [config [type path]]
  (let [{:keys [webhook-url webhook-credentials]} config]
    (call config
          {:method :post
           :path (subscriptions-path config)
           :body {:type type
                  :url (str webhook-url path)
                  :retry true
                  :secret (:secret webhook-credentials)
                  :hmacAlgorithm (:algorithm webhook-credentials)}})))

(defn- ensure-subscribed
  "Subscribe each notification Modulr does not hold for this adapter's
  URL, so one a simulator forgot on restart is subscribed again, and
  mark the adapter ready once Modulr holds them all. Returns `:held`,
  `:missing` where Modulr refused one, or `:failed` where it did not
  answer."
  [config]
  (let [[outcome result] (call config
                               {:method :get
                                :path (subscriptions-path config)})]
    (case outcome
      :ok
      (let [held (set (map (juxt :type :url) (:content result)))
            absent (remove (fn [[type path]]
                             (contains? held
                                        [type
                                         (str (:webhook-url config) path)]))
                           webhook/paths)
            outcomes (mapv (fn [subscription]
                             (log/info "Subscribing to a Modulr notification"
                                       {:type (first subscription)})
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
   :system/config {:modulr-url system/required-component
                   :credentials system/required-component
                   :customer-id system/required-component
                   :webhook-url system/required-component
                   :webhook-credentials system/required-component
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
                         (commands/->ModulrCommandProcessor config))))
   :system/config {:schemas system/required-component
                   :record-db system/required-component
                   :record-store system/required-component
                   :payment-provider system/required-component
                   :product-code nil}
   :system/instance-schema some?})

(system/defcomponents :modulr-adapter
                      {:readiness readiness
                       :registrar registrar
                       :command-processor command-processor})
