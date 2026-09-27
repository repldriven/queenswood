(ns com.repldriven.queenswood.clearbank-adapter.system
  (:require
    [com.repldriven.queenswood.clearbank-adapter.commands
     :as commands]
    [com.repldriven.queenswood.clearbank-adapter.provider :as provider]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private re-register-poll-ms 30000)

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

(defn- register-webhook-with-retry
  "Retries a single webhook registration. Adapter and simulator
  start in parallel, so the first attempt may hit a not-yet-ready
  simulator and silently leave the webhook unregistered. Caps at
  ~2 minutes (24 attempts × 5s)."
  [simulator-url webhook-url {:keys [type] :as webhook}]
  (loop [attempt 1]
    (let [res (register-webhook simulator-url webhook-url webhook)]
      (cond
       (ok-response? res)
       (do (log/info "Registered ClearBank webhook"
                     {:type type :status (:status res) :attempt attempt})
           res)

       (< attempt 24)
       (do (log/warn "ClearBank webhook registration not ready; retrying"
                     {:type type
                      :simulator simulator-url
                      :attempt attempt
                      :status (:status res)
                      :error (when (error/anomaly? res) (error/kind res))})
           (Thread/sleep 5000)
           (recur (inc attempt)))

       :else
       (do (log/error "ClearBank webhook registration gave up after 24 attempts"
                      {:type type :simulator simulator-url :last res})
           res)))))

(defn- registered?
  "Polls the simulator's webhook list and returns true iff EVERY
  expected webhook (by `:type` and resolved URL) is currently
  present. Used by the periodic re-register loop to detect
  simulator state loss — its webhook map is in-memory, so a
  simulator restart drops every entry and callbacks would
  otherwise stop arriving."
  [simulator-url webhook-url webhooks]
  (let [res (http/request {:method :get
                           :url (str simulator-url "/v1/webhooks")
                           :headers {"Accept" "application/json"}})
        registered (try (some-> res
                                :body
                                json/read-str
                                (get "webhooks"))
                        (catch Throwable _ nil))
        present? (fn [{:keys [type] :as webhook}]
                   (some (fn [w]
                           (and (= type (get w "type"))
                                (= (expected-url webhook-url webhook)
                                   (get w "url"))))
                         registered))]
    (and (ok-response? res) (every? present? webhooks))))

(defn- re-register-missing
  [simulator-url webhook-url webhooks]
  (doseq [webhook webhooks]
    (let [res (register-webhook simulator-url webhook-url webhook)]
      (if (ok-response? res)
        (log/info "Re-registered ClearBank webhook"
                  {:type (:type webhook) :status (:status res)})
        (log/error "ClearBank webhook re-registration failed"
                   {:type (:type webhook) :res res})))))

(defn- start-re-register-loop
  "Daemon thread that polls the simulator every `poll-ms` and
  re-registers any of `webhooks` that are missing from its
  registry. The simulator stores webhooks keyed by
  `[sort-code type]` so re-POSTing is idempotent at storage level
  (no duplicates accumulate); we re-register the FULL set to
  recover cleanly from total state loss in one pass. Returns the
  thread so the registrar's stop can interrupt it; also daemon-typed
  as a backstop against a leak past process exit."
  [simulator-url webhook-url webhooks poll-ms]
  (doto (Thread.
         (fn []
           ;; The interrupt is the stop signal, so it ends the loop by
           ;; value rather than by throwing. Letting it propagate out of
           ;; `run` would have the default handler print a stack trace on
           ;; every clean shutdown.
           (loop []
             (when
               (try
                 (Thread/sleep poll-ms)
                 (when-not (registered? simulator-url webhook-url webhooks)
                   (log/warn
                    "One or more ClearBank webhooks missing; re-registering all"
                    {:simulator simulator-url})
                   (re-register-missing simulator-url webhook-url webhooks))
                 true
                 (catch InterruptedException _ false)
                 (catch Throwable t
                   (log/error
                    t
                    "ClearBank webhook poll iteration threw; continuing")
                   true))
               (recur)))))
    (.setDaemon true)
    (.setName "clearbank-webhook-re-register")
    (.start)))

(def ^:private readiness
  {:system/start (fn [{:system/keys [instance]}] (or instance (atom false)))
   :system/instance-schema some?})

(def ^:private registrar
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or instance
         (let [{:keys [simulator-url webhook-url webhooks readiness]} config
               loop-thread (atom nil)
               stopped? (atom false)]
           (log/info "Registering ClearBank webhooks (async)"
                     {:simulator simulator-url :webhook webhook-url})
           (let [fut (future
                      (let [results (doall (map (fn [webhook]
                                                  (register-webhook-with-retry
                                                   simulator-url
                                                   webhook-url
                                                   webhook))
                                                webhooks))]
                        (when (and (every? ok-response? results)
                                   (not @stopped?))
                          (reset! readiness true)
                          (log/info "ClearBank webhook registration complete")
                          (reset! loop-thread (start-re-register-loop
                                               simulator-url
                                               webhook-url
                                               webhooks
                                               re-register-poll-ms)))))]
             {:fut fut :loop-thread loop-thread :stopped? stopped?}))))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [fut loop-thread stopped?]} instance]
                    (reset! stopped? true)
                    (future-cancel fut)
                    (when-let [^Thread t @loop-thread] (.interrupt t))))
   :system/config {:simulator-url system/required-component
                   :webhook-url system/required-component
                   :webhooks nil
                   :readiness system/required-component}
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
