(ns com.repldriven.queenswood.onfido-relay.outbound
  (:require
    [com.repldriven.queenswood.onfido-relay.store :as store]

    [com.repldriven.queenswood.intent-queue.interface :as intent-queue]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str])
  (:import
    (java.net URLEncoder)
    (java.nio.charset StandardCharsets)
    (java.time Instant)))

(def ^:private default-poll-ms 200)
(def ^:private default-max-attempts 10)
(def ^:private default-initial-backoff-ms 1000)
(def ^:private default-max-backoff-ms 60000)
(def ^:private default-hand-off-ttl-ms (* 15 60 1000))

(def ^:private bank-tag "bank:")
(def ^:private verification-tag "verification:")

(defn- classify
  [url res]
  (let [status (:status res)]
    (cond
     (error/anomaly? res)
     (error/fail :idv/unavailable
                 {:message "Identity verification provider unreachable"
                  :url url
                  :cause res})

     (nil? status)
     res

     (= 429 status)
     (error/fail :idv/rate-limited
                 {:message "Identity verification provider rate limited"
                  :url url
                  :status status
                  :body (:body res)})

     (>= status 500)
     (error/fail :idv/unavailable
                 {:message "Identity verification provider unavailable"
                  :url url
                  :status status
                  :body (:body res)})

     (>= status 400)
     (error/fail :idv/http
                 {:message "Identity verification provider rejected request"
                  :url url
                  :status status
                  :body (:body res)})

     :else
     res)))

(defn- call
  [{:keys [onfido-url api-token]} method path body]
  (let [url (str onfido-url "/v3.6" path)]
    (error/try-nom
     :idv/unavailable
     "Identity verification provider call failed"
     (let-nom> [res (classify url
                              (http/request
                               (cond-> {:method method
                                        :url url
                                        :headers
                                        {"Content-Type" "application/json"
                                         "Authorization"
                                         (str "Token token=" api-token)}}
                                       body
                                       (assoc :body (json/write-str body)))))]
       (http/res->edn res)))))

(defn- encode
  [v]
  (URLEncoder/encode (str v) StandardCharsets/UTF_8))

(defn- covers
  [workflow]
  (set (concat (:verifies workflow) (:screens workflow))))

(defn uncovered
  [workflows declaration]
  (set/difference (set (concat (:verifies declaration) (:screens declaration)))
                  (reduce set/union #{} (map covers workflows))))

(defn select-workflow
  [workflows requested]
  (->> workflows
       (filter (fn [workflow] (set/subset? (set requested) (covers workflow))))
       (sort-by (fn [workflow] (count (covers workflow))))
       first))

(defn- full-first-name
  [first-name middle-names]
  (if (str/blank? middle-names)
    first-name
    (str/trim (str first-name " " middle-names))))

(defn- address->onfido
  [address]
  (let [{:keys! [street town postcode country]
         :keys [flat-number building-number building-name sub-street state]}
        address]
    (utility/assoc-some {:street street
                         :town town
                         :postcode postcode
                         :country country}
                        :flat_number flat-number
                        :building_number building-number
                        :building_name building-name
                        :sub_street sub-street
                        :state state)))

(defn applicant
  [data]
  (let [{:keys! [first-name last-name]
         :keys [middle-names date-of-birth address email]}
        data]
    (utility/assoc-some {:first_name (full-first-name first-name middle-names)
                         :last_name last-name}
                        :dob date-of-birth
                        :email (when-not (str/blank? email) email)
                        :address (when address (address->onfido address)))))

(defn- expires-at
  [{:keys [hand-off-ttl-ms]}]
  (+ (utility/now) (or hand-off-ttl-ms default-hand-off-ttl-ms)))

(defn workflow-run
  [config workflow applicant-id data]
  (let [{:keys! [bank-id verification-id party-id] :keys [return-url]} data]
    {:workflow_id (:id workflow)
     :applicant_id applicant-id
     :customer_user_id party-id
     :tags [(str bank-tag bank-id) (str verification-tag verification-id)]
     :link (utility/assoc-some {:expires_at (str (Instant/ofEpochMilli
                                                  (expires-at config)))}
                               :completed_redirect_url
                               (when-not (str/blank? return-url)
                                 return-url))}))

(defn- waiting?
  [now {:keys [status link]}]
  (and (= "awaiting_input" status)
       (:url link)
       (or (nil? (:expires_at link))
           (< now (.toEpochMilli (Instant/parse (:expires_at link)))))))

(defn- open-run
  [config verification-id]
  (let-nom> [found (call config
                         :get
                         (str "/workflow_runs?tags="
                              (encode (str verification-tag verification-id)))
                         nil)]
    (some (fn [run] (when (waiting? (utility/now) run) run))
          (if (sequential? found) found (:workflow_runs found)))))

(defn- start-run
  [config workflow data]
  (let-nom> [created (call config :post "/applicants" (applicant data))]
    (call config
          :post
          "/workflow_runs"
          (workflow-run config workflow (:id created) data))))

(defn- run-for
  [config workflow data]
  (let-nom> [existing (open-run config (:verification-id data))]
    (or existing (start-run config workflow data))))

(defn- backoff-ms
  [config attempts]
  (let [{:keys [initial-backoff-ms max-backoff-ms]} config
        initial (or initial-backoff-ms default-initial-backoff-ms)
        cap (or max-backoff-ms default-max-backoff-ms)]
    (reduce (fn [delay _] (min cap (* 2 delay)))
            (min cap initial)
            (range (dec attempts)))))

(defn- event
  [config now intent descriptor]
  (let [{:keys [schemas]} config
        {:keys [intent-id traceparent]} intent
        {:keys [event-name dedup-key data]} descriptor]
    (let-nom> [payload (avro/serialize (get schemas event-name) data)]
      (utility/assoc-some {:outbox-id (str (utility/uuidv7))
                           :dedup-key dedup-key
                           :event-name event-name
                           :payload payload
                           :correlation-id (str (utility/uuidv7))
                           :causation-id intent-id
                           :created-at now}
                          :traceparent
                          (not-empty traceparent)))))

(defn- finish
  [config now intent outcome descriptor]
  (let [{:keys [intent-id attempts]} intent]
    (if (nil? descriptor)
      (store/finish config intent-id "pending" outcome attempts nil)
      (let-nom> [e (event config now intent descriptor)]
        (store/finish config intent-id "pending" outcome attempts e)))))

(defn- give-up?
  [config attempts]
  (>= attempts (or (:max-attempts config) default-max-attempts)))

(defn- retry
  [config now intent attempts reason]
  (let [{:keys [intent-id]} intent]
    (log/warn "Onfido call failed; will retry"
              {:intent-id intent-id :attempt attempts :reason reason})
    (store/mark-attempt config
                        intent-id
                        attempts
                        (+ now (backoff-ms config attempts)))))

(defn- session-opened
  [config data run]
  (let [{:keys! [bank-id verification-id session-id]} data
        {:keys [url expires_at]} (:link run)]
    {:event-name "idv-session-opened"
     :dedup-key (str session-id ":opened")
     :data {:bank-id bank-id
            :verification-id verification-id
            :session-id session-id
            :url url
            :expires-at (if expires_at
                          (.toEpochMilli (Instant/parse
                                          expires_at))
                          (expires-at config))}}))

(defn- session-failed
  [data reason]
  (let [{:keys! [bank-id verification-id session-id]} data]
    {:event-name "idv-session-failed"
     :dedup-key (str session-id ":failed")
     :data {:bank-id bank-id
            :verification-id verification-id
            :session-id session-id
            :reason reason}}))

(defn- refused?
  "True for a failure retrying cannot mend: the provider refused the
  request, or no configured workflow covers it."
  [res]
  (contains? #{:idv/http :idv/unsupported-criteria} (error/kind res)))

(defn- relay-check
  "Make `intent`'s call, returning the intent with the status it was left
  at, or an anomaly. An intent with no session reports nothing."
  [config now intent]
  (let [{:keys [intent-id request]} intent
        data (edn/read-string request)
        session? (some? (:session-id data))
        attempts (inc (or (:attempts intent) 0))
        intent (assoc intent :attempts attempts)
        workflow (select-workflow (:workflows config)
                                  (concat (:verifications data)
                                          (:screenings data)))
        res (if workflow
              (run-for config workflow data)
              (error/fail :idv/unsupported-criteria
                          {:message "No configured workflow covers the request"
                           :verifications (:verifications data)
                           :screenings (:screenings data)}))
        reason (when (error/anomaly? res) (:message (error/payload res)))
        failed (fn [reason] (when session? (session-failed data reason)))]
    (cond
     (not (error/anomaly? res))
     (finish config
             now
             intent
             "settled"
             (when session? (session-opened config data res)))

     (refused? res)
     (do (log/error "Onfido refused the verification check"
                    {:intent-id intent-id :reason reason})
         (finish config now intent "failed" (failed reason)))

     (give-up? config attempts)
     (do (log/error "Onfido call giving up after max attempts"
                    {:intent-id intent-id :reason reason})
         (finish config
                 now
                 intent
                 "failed"
                 (failed (str "Undelivered: " reason))))

     :else
     (retry config now intent attempts reason))))

(defn- in-intent-trace
  [span-name intent f]
  (telemetry/with-span-parent span-name
                              (telemetry/extract-parent-context intent)
                              (utility/assoc-some {}
                                                  "intent.id"
                                                  (:intent-id intent))
                              f))

(defn- checked
  "Run `f` on `intent`, failing the intent where its call throws, so it no
  longer holds the intents behind it."
  [config intent f]
  (let [res (error/try-nom-ex :onfido-relay/intent
                              Exception
                              "Onfido intent could not be relayed"
                              (f intent))]
    (if (error/anomaly? res)
      (let [{:keys [intent-id status attempts]} intent]
        (log/error "Onfido intent could not be relayed; failing it"
                   {:intent-id intent-id :anomaly res})
        (store/finish config intent-id status "failed" attempts nil)
        (assoc intent :status "failed"))
      res)))

(defn drain-once
  [config now]
  (let [pending (store/intents-with-status config "pending")]
    (when-not (error/anomaly? pending)
      (intent-queue/drain pending
                          now
                          {:settles-first? (constantly false)
                           :run (fn [i]
                                  (in-intent-trace "onfido-outbound"
                                                   i
                                                   (fn []
                                                     (checked
                                                      config
                                                      i
                                                      (fn [i]
                                                        (relay-check
                                                         config
                                                         now
                                                         i))))))}))))

(defn- start-loop
  [config]
  (let [running (atom true)
        poll-ms (or (:poll-ms config) default-poll-ms)
        t (doto (Thread.
                 (fn []
                   (while @running
                     (try (drain-once config (utility/now))
                          (catch Exception e
                            (log/error e
                                       "Onfido relay drain threw; continuing")))
                     (try (when @running (Thread/sleep poll-ms))
                          (catch InterruptedException _
                            (reset! running false))))))
            (.setDaemon true)
            (.setName "onfido-outbound-relay")
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t))}))

(defn start-runner
  [config]
  (let [missing (uncovered (:workflows config) (:idv-provider config))]
    (if (seq missing)
      (error/fail :idv/unsupported-criteria
                  {:message (str "No configured workflow establishes "
                                 (str/join ", " (sort missing)))
                   :missing missing})
      (start-loop config))))

(defn- tagged
  [tags prefix]
  (some (fn [tag]
          (when (str/starts-with? tag prefix) (subs tag (count prefix))))
        tags))

(defn- items
  [res k]
  (if (sequential? res) res (get res k)))

(defn read-run
  [config run-id]
  (let-nom> [run (call config :get (str "/workflow_runs/" run-id) nil)
             checks (call config
                          :get
                          (str "/checks?applicant_id="
                               (encode (:applicant_id
                                        run)))
                          nil)
             reports (reduce
                      (fn [acc {:keys [id]}]
                        (let [res (call config
                                        :get
                                        (str "/reports?check_id=" (encode id))
                                        nil)]
                          (if (error/anomaly? res)
                            (reduced res)
                            (into acc (items res :reports)))))
                      []
                      (items checks :checks))]
    {:run run
     :bank-id (tagged (:tags run) bank-tag)
     :verification-id (tagged (:tags run) verification-tag)
     :reports reports}))
