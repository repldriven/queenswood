(ns com.repldriven.queenswood.modulr-adapter.system
  (:require
    [com.repldriven.queenswood.modulr-adapter.commands :as commands]
    [com.repldriven.queenswood.modulr-adapter.provider :as provider]
    [com.repldriven.queenswood.modulr-adapter.webhook.routes :as webhook]

    [com.repldriven.queenswood.modulr-relay.interface :as relay]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private re-register-poll-ms 30000)

(def ^:private register-attempts 24)

(def ^:private register-retry-ms 5000)

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

(defn- subscribe-with-retry
  "Subscribe one notification type, retrying while Modulr is not
  reachable: the adapter and a simulator start together, so the first
  attempt may find nothing listening."
  [config subscription]
  (loop [attempt 1]
    (let [[outcome result] (subscribe config subscription)]
      (cond
       (= :ok outcome)
       (do (log/info "Subscribed to a Modulr notification"
                     {:type (first subscription)})
           true)

       (< attempt register-attempts)
       (do (log/warn
            "Modulr notification subscription not ready; retrying"
            {:type (first subscription) :attempt attempt :reason result})
           (Thread/sleep (long register-retry-ms))
           (recur (inc attempt)))

       :else
       (do (log/error "Modulr notification subscription gave up"
                      {:type (first subscription) :reason result})
           false)))))

(defn- missing
  "The subscriptions Modulr does not hold for this adapter's URL, so a
  simulator that restarted and forgot them is told again."
  [config]
  (let [[outcome result] (call config
                               {:method :get :path (subscriptions-path config)})
        held (when (= :ok outcome)
               (set (map (juxt :type :url) (:content result))))]
    (if (nil? held)
      []
      (remove (fn [[type path]]
                (contains? held [type (str (:webhook-url config) path)]))
              webhook/paths))))

(defn- start-re-subscribe-loop
  [config stopped?]
  (doto (Thread.
         (fn []
           (loop []
             (when
               (try
                 (Thread/sleep (long re-register-poll-ms))
                 (when-not @stopped?
                   (doseq [subscription (missing config)]
                     (log/warn "Modulr notification missing; subscribing again"
                               {:type (first subscription)})
                     (subscribe config subscription)))
                 true
                 (catch InterruptedException _ false)
                 (catch Throwable t
                   (log/error t "Modulr subscription check threw; continuing")
                   true))
               (recur)))))
    (.setDaemon true)
    (.setName "modulr-notification-subscriptions")
    (.start)))

(def ^:private readiness
  {:system/start (fn [{:system/keys [instance]}] (or instance (atom false)))
   :system/instance-schema some?})

(def ^:private registrar
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or instance
         (let [stopped? (atom false)
               loop-thread (atom nil)
               fut (future
                    (when (and (every? (fn [s] (subscribe-with-retry config s))
                                       webhook/paths)
                               (not @stopped?))
                      (reset! (:readiness config) true)
                      (reset! loop-thread (start-re-subscribe-loop config
                                                                   stopped?))))]
           {:fut fut :loop-thread loop-thread :stopped? stopped?})))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [fut loop-thread stopped?]} instance]
                    (reset! stopped? true)
                    (future-cancel fut)
                    (when-let [^Thread t @loop-thread] (.interrupt t))))
   :system/config {:modulr-url system/required-component
                   :credentials system/required-component
                   :customer-id system/required-component
                   :webhook-url system/required-component
                   :webhook-credentials system/required-component
                   :readiness system/required-component}
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
