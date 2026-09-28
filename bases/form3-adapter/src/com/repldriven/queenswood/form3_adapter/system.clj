(ns com.repldriven.queenswood.form3-adapter.system
  (:require
    [com.repldriven.queenswood.form3-adapter.commands :as commands]
    [com.repldriven.queenswood.form3-adapter.provider :as provider]
    [com.repldriven.queenswood.form3-adapter.webhook.routes :as webhook]

    [com.repldriven.queenswood.form3-relay.interface :as relay]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private re-subscribe-poll-ms 30000)

(def ^:private subscribe-attempts 24)

(def ^:private subscribe-retry-ms 5000)

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

(defn- subscribe-with-retry
  "Subscribe to one record and event type, retrying while Form3 is not
  reachable: the adapter and a simulator start together, so the first
  attempt may find nothing listening."
  [config subscription]
  (loop [attempt 1]
    (let [[outcome result] (subscribe config subscription)]
      (cond
       (= :ok outcome)
       (do (log/info "Subscribed to a Form3 notification"
                     {:subscription subscription})
           true)

       (< attempt subscribe-attempts)
       (do (log/warn "Form3 subscription not ready; retrying"
                     {:subscription subscription
                      :attempt attempt
                      :reason result})
           (Thread/sleep (long subscribe-retry-ms))
           (recur (inc attempt)))

       :else
       (do (log/error "Form3 subscription gave up"
                      {:subscription subscription :reason result})
           false)))))

(defn- missing
  "The subscriptions Form3 does not hold for this adapter's URL, so a
  simulator that restarted and forgot them is told again."
  [config]
  (let [[outcome result] (call config {:method :get :path subscriptions-path})
        held (when (= :ok outcome)
               (set (map (fn [{:keys [attributes]}]
                           [(:record_type attributes)
                            (:event_type attributes)
                            (:callback_uri attributes)])
                         (:data result))))]
    (if (nil? held)
      []
      (remove (fn [[record-type event-type]]
                (contains? held [record-type event-type (callback-uri config)]))
              webhook/subscriptions))))

(defn- start-re-subscribe-loop
  [config stopped?]
  (doto (Thread.
         (fn []
           (loop []
             (when
               (try
                 (Thread/sleep (long re-subscribe-poll-ms))
                 (when-not @stopped?
                   (doseq [subscription (missing config)]
                     (log/warn "Form3 subscription missing; subscribing again"
                               {:subscription subscription})
                     (subscribe config subscription)))
                 true
                 (catch InterruptedException _ false)
                 (catch Throwable t
                   (log/error t "Form3 subscription check threw; continuing")
                   true))
               (recur)))))
    (.setDaemon true)
    (.setName "form3-notification-subscriptions")
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
                                       webhook/subscriptions)
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
   :system/config {:form3-url system/required-component
                   :credentials system/required-component
                   :webhook-url system/required-component
                   :readiness system/required-component}
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
