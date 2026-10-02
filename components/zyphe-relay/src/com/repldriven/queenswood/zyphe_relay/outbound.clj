(ns com.repldriven.queenswood.zyphe-relay.outbound
  "The outbound Zyphe relay: creates or resumes a verification request
  for each pending intent, OUTSIDE any FDB transaction, on the smallest
  configured flow that covers the verifications and screenings the
  intent asks for. The request carries the bank, verification and
  session ids as `customData`, which Zyphe echoes on every webhook, and
  installs a session webhook that delivers the run's events, signed with
  the adapter's secret, to the adapter. The hand-off it composes from
  the reply goes to the outbox as `idv-session-opened`."
  (:require
    [com.repldriven.queenswood.zyphe-relay.store :as store]

    [com.repldriven.queenswood.intent-queue.interface :as intent-queue]

    [com.repldriven.queenswood.zyphe-webhook.interface :as zyphe-webhook]

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
    (java.nio.charset StandardCharsets)))

(def ^:private default-poll-ms 200)
(def ^:private default-max-attempts 10)
(def ^:private default-initial-backoff-ms 1000)
(def ^:private default-max-backoff-ms 60000)

(defn- classify
  "Turn a provider response into itself or the anomaly that names what
  went wrong. Kinds stay in the `:idv/*` namespace rather than naming
  the vendor — they surface as the API's RFC 9457 `type`, and the
  identity provider consuming this channel must not change the contract
  (ADR-0020). An unreachable provider, a 5xx and a 429 are retryable and
  say so, a remaining 4xx means our request is wrong and keeps the
  call-site name."
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

(def ^:private default-hand-off-ttl-ms (* 15 60 1000))

(defn- covers
  [flow]
  (set (concat (:verifies flow) (:screens flow))))

(defn uncovered
  "What `declaration` says the provider establishes that no configured
  flow covers, as a set of names, empty when the flows cover it."
  [flows declaration]
  (set/difference (set (concat (:verifies declaration) (:screens declaration)))
                  (reduce set/union #{} (map covers flows))))

(defn select-flow
  "The configured flow with the fewest verifications and screenings
  that covers `requested`, or nil when none does."
  [flows requested]
  (->> flows
       (filter (fn [flow] (set/subset? (set requested) (covers flow))))
       (sort-by (fn [flow] (count (covers flow))))
       first))

(defn create-url
  "Zyphe's create-or-resume verification request endpoint for `flow-id`,
  in sandbox or production."
  [{:keys [zyphe-url sandbox]} flow-id]
  (str zyphe-url "/sdk/flow/" flow-id "/vr/create?sandbox=" sandbox))

(defn verification-request
  "The create-verification-request body for a submit-idv-check. The
  person is identified by party id as an external-id credential and by
  email where there is one, the bank, verification and session ids ride
  as `customData`, and the session webhook is keyed by the adapter's
  secret. Creating again for the same identity resumes the run, so a
  retried intent or a second session does not start a second one."
  [config data]
  (let [{:keys [adapter-url webhook-secret]} config
        {:keys! [bank-id verification-id party-id] :keys [session-id email]}
        data]
    (utility/assoc-some
     {:credentials [{:type "EXTERNAL_ID" :externalId party-id}]
      :customData (utility/assoc-some {:bankId bank-id
                                       :verificationId verification-id}
                                      :sessionId
                                      session-id)
      :webhook {:url (str adapter-url zyphe-webhook/path)
                :secret webhook-secret
                :payloadVersion "V2"}}
     :email
     (when-not (str/blank? email) email))))

(defn- encode
  [v]
  (URLEncoder/encode (str v) StandardCharsets/UTF_8))

(defn hand-off-url
  "The hosted-flow URL the person is handed to, per Zyphe's session URL:
  the run, its token and access signature, the person's email, the
  tenant's return URL, and a full-screen layout for a mobile WebView."
  [{:keys [verify-url sandbox]} {:keys [channel return-url email]} reply]
  (let [{:keys [verificationRequest zypheToken zypheAccessSig flowSlug]} reply
        params (cond-> [["zypheVr" (:id verificationRequest)]
                        ["zypheToken" zypheToken]
                        ["zypheAccessSig" zypheAccessSig]]
                       (not (str/blank? email))
                       (conj ["zypheEmail" email])

                       (not (str/blank? return-url))
                       (conj ["zypheHandoffBaseUrl" return-url])

                       (= "mobile" channel)
                       (conj ["zypheFullscreen" "true"]))]
    (str verify-url
         (when (= "true" (str sandbox)) "/sandbox")
         "/flow/"
         flowSlug
         "?"
         (str/join "&"
                   (map (fn [[k v]] (str k "=" (encode v))) params)))))

(defn- submit-idv-check
  [config flow data]
  (let [url (create-url config (:id flow))
        body (json/write-str (verification-request config data))]
    (error/try-nom
     :idv/unavailable
     "Identity verification provider call failed"
     (let-nom> [res (classify url
                              (http/request
                               {:method :post
                                :url url
                                :headers {"Content-Type" "application/json"
                                          "x-api-key" (:api-key config)}
                                :body body}))]
       (http/res->edn res)))))

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
    (log/warn "Zyphe call failed; will retry"
              {:intent-id intent-id :attempt attempts :reason reason})
    (store/mark-attempt config
                        intent-id
                        attempts
                        (+ now (backoff-ms config attempts)))))

(defn- session-opened
  [config now data reply]
  (let [{:keys [hand-off-ttl-ms]} config
        {:keys! [bank-id verification-id session-id]} data]
    {:event-name "idv-session-opened"
     :dedup-key (str session-id ":opened")
     :data {:bank-id bank-id
            :verification-id verification-id
            :session-id session-id
            :url (hand-off-url config data reply)
            :expires-at (+ now
                           (or hand-off-ttl-ms
                               default-hand-off-ttl-ms))}}))

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
  request, or no configured flow covers it."
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
        flow (select-flow (:flows config)
                          (concat (:verifications data) (:screenings data)))
        res (if flow
              (submit-idv-check config flow data)
              (error/fail :idv/unsupported-criteria
                          {:message "No configured flow covers the request"
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
             (when session? (session-opened config now data res)))

     (refused? res)
     (do (log/error "Zyphe refused the verification check"
                    {:intent-id intent-id :reason reason})
         (finish config now intent "failed" (failed reason)))

     (give-up? config attempts)
     (do (log/error "Zyphe call giving up after max attempts"
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
  (let [res (error/try-nom-ex :zyphe-relay/intent
                              Exception
                              "Zyphe intent could not be relayed"
                              (f intent))]
    (if (error/anomaly? res)
      (let [{:keys [intent-id status attempts]} intent]
        (log/error "Zyphe intent could not be relayed; failing it"
                   {:intent-id intent-id :anomaly res})
        (store/finish config intent-id status "failed" attempts nil)
        (assoc intent :status "failed"))
      res)))

(defn drain-once
  "Relay each pending intent once, oldest first, holding one for a
  verification while an earlier one for it is still pending. The Zyphe
  call per intent runs outside any FDB transaction."
  [config now]
  (let [pending (store/intents-with-status config "pending")]
    (when-not (error/anomaly? pending)
      (intent-queue/drain pending
                          now
                          {:settles-first? (constantly false)
                           :run (fn [i]
                                  (in-intent-trace "zyphe-outbound"
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
                                       "Zyphe relay drain threw; continuing")))
                     (try (when @running (Thread/sleep poll-ms))
                          (catch InterruptedException _
                            (reset! running false))))))
            (.setDaemon true)
            (.setName "zyphe-outbound-relay")
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t))}))

(defn start-runner
  "Start the daemon poll loop that drains pending outbound intents.
  Returns `{:stop fn}`, or an `:idv/unsupported-criteria` anomaly when
  the configured flows do not cover what the provider declaration says
  the adapter establishes."
  [config]
  (let [missing (uncovered (:flows config) (:idv-provider config))]
    (if (seq missing)
      (error/fail :idv/unsupported-criteria
                  {:message (str "No configured flow establishes "
                                 (str/join ", " (sort missing)))
                   :missing missing})
      (start-loop config))))
