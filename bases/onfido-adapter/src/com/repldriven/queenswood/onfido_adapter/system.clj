(ns com.repldriven.queenswood.onfido-adapter.system
  (:require
    [com.repldriven.queenswood.onfido-adapter.commands :as commands]

    [com.repldriven.queenswood.onfido-webhook.interface :as onfido-webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system]))

(def ^:private re-register-poll-ms 30000)

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

(defn- registered?
  "Polls the simulator's webhook list and returns true iff the
  adapter's URL is currently registered. Used by the periodic
  re-register loop to detect simulator state loss (its registry is
  in-memory; a simulator pod bounce wipes everything and the
  adapter-side registration silently disappears)."
  [onfido-url adapter-url]
  (let [res (http/request {:method :get
                           :url (str onfido-url "/v3.6/webhooks")
                           :headers {"Accept" "application/json"}})
        url (adapter-webhook-url adapter-url)]
    (and (map? res)
         (number? (:status res))
         (< (:status res) 400)
         (try (some (fn [w] (= url (get w "url")))
                    (some-> res
                            :body
                            json/read-str
                            (get "webhooks")))
              (catch Throwable _ false)))))

(defn- ok-response?
  [res]
  (and (map? res) (number? (:status res)) (< (:status res) 400)))

(defn- register-webhook-with-retry
  "Retries webhook registration with the Onfido (or simulator)
  endpoint. The two services start in parallel so registration
  often races against simulator readiness; without retry the
  adapter fails to register on the first attempt and the
  simulator silently has nowhere to deliver its
  webhooks. Caps at ~2 minutes (24 attempts × 5s)."
  [onfido-url adapter-url]
  (loop [attempt 1]
    (let [res (register-webhook onfido-url adapter-url)]
      (cond
       (ok-response? res)
       (do (log/info "Registered Onfido webhook"
                     {:adapter adapter-url
                      :status (:status res)
                      :attempt attempt})
           res)

       (< attempt 24)
       (do (log/warn "Onfido webhook registration not ready; retrying"
                     {:onfido onfido-url
                      :attempt attempt
                      :status (:status res)
                      :error (when (error/anomaly? res) (error/kind res))})
           (Thread/sleep 5000)
           (recur (inc attempt)))

       :else
       (do (log/error "Onfido webhook registration gave up after 24 attempts"
                      {:onfido onfido-url :last res})
           res)))))

(defn- start-re-register-loop
  "Daemon thread that polls the simulator every `poll-ms` and
  re-registers the adapter's webhook if its URL has dropped from
  the simulator's registry. Covers the simulator-bounce silent-loss
  case: the simulator's webhook map is in-memory, so a restart
  drops every registration and callbacks would otherwise stop
  arriving with no error visible to the adapter. With the
  simulator-side URL dedup, re-registration is a cheap no-op when
  the URL is already present, so polling doesn't accumulate state.
  Returns the thread so the registrar's stop can interrupt it;
  also daemon-typed as a backstop against a leak past process exit."
  [onfido-url adapter-url poll-ms]
  (doto (Thread.
         (fn []
           ;; The interrupt is the stop signal, so it ends the loop by
           ;; value rather than by throwing. Letting it propagate out of
           ;; `run` would have the default handler print a stack trace on
           ;; every clean shutdown.
           (loop []
             (when (try
                     (Thread/sleep poll-ms)
                     (when-not (registered? onfido-url adapter-url)
                       (log/warn
                        "Onfido webhook missing from simulator; re-registering"
                        {:onfido onfido-url :adapter adapter-url})
                       (let [res (register-webhook onfido-url adapter-url)]
                         (if (ok-response? res)
                           (log/info "Re-registered Onfido webhook"
                                     {:adapter adapter-url
                                      :status (:status res)})
                           (log/error "Onfido webhook re-registration failed"
                                      {:adapter adapter-url :res res}))))
                     true
                     (catch InterruptedException _ false)
                     (catch Throwable t
                       (log/error
                        t
                        "Onfido webhook poll iteration threw; continuing")
                       true))
               (recur)))))
    (.setDaemon true)
    (.setName "onfido-webhook-re-register")
    (.start)))

(def ^:private readiness
  {:system/start (fn [{:system/keys [instance]}] (or instance (atom false)))
   :system/instance-schema some?})

(def ^:private registrar
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or instance
         (let [{:keys [onfido-url adapter-url readiness]} config
               loop-thread (atom nil)
               stopped? (atom false)]
           (log/info "Registering Onfido webhook (async)"
                     {:onfido onfido-url :adapter adapter-url})
           (let [fut (future
                      (let [res (register-webhook-with-retry onfido-url
                                                             adapter-url)]
                        (when (and (ok-response? res) (not @stopped?))
                          (reset! readiness true)
                          (log/info "Onfido webhook registration complete")
                          (reset! loop-thread (start-re-register-loop
                                               onfido-url
                                               adapter-url
                                               re-register-poll-ms)))))]
             {:fut fut :loop-thread loop-thread :stopped? stopped?}))))
   :system/stop (fn [{:system/keys [instance]}]
                  (when-let [{:keys [fut loop-thread stopped?]} instance]
                    (reset! stopped? true)
                    (future-cancel fut)
                    (when-let [^Thread t @loop-thread] (.interrupt t))))
   :system/config {:onfido-url system/required-component
                   :adapter-url system/required-component
                   :readiness system/required-component}
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
