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

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]
    [com.repldriven.queenswood.zyphe-webhook.interface :as zyphe-webhook]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str])
  (:import
    (java.net URLEncoder)
    (java.nio.charset StandardCharsets)))


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

(defn- request
  [intent]
  (edn/read-string (:request intent)))

(defn- check
  [config _now intent]
  (let [data (request intent)
        criteria (concat (:verifications data) (:screenings data))
        flow (select-flow (:flows config) criteria)
        res (if flow
              (submit-idv-check config flow data)
              (error/fail :idv/unsupported-criteria
                          {:message "No configured flow covers the request"
                           :verifications (:verifications data)
                           :screenings (:screenings data)}))]
    (cond
     (not (error/anomaly? res))
     [:answered res]

     (refused? res)
     [:refused (:message (error/payload res))]

     :else
     [:retry (:message (error/payload res))])))

(defn- checked
  "The check settles, reporting the hand-off where the intent has a
  session."
  [config now intent reply]
  (let [data (request intent)]
    {:status "settled"
     :event (when (:session-id data) (session-opened config now data reply))}))

(defn- check-failed
  [_config _now intent failure reason]
  (let [data (request intent)]
    {:status "failed"
     :event (when (:session-id data)
              (session-failed data
                              (if (= :undelivered failure)
                                (str "Undelivered: " reason)
                                reason)))}))

(intent-poller/defoperations
 :zyphe
 {"check" {:call check :answered checked :failed check-failed}})

(defn- runner-config
  [config]
  (assoc config
         :adapter :zyphe
         :store store/spec
         :default-operation "check"))

(defn drain-once
  "Relay each pending intent once, oldest first, holding one for a
  verification while an earlier one for it is still pending. The Zyphe
  call per intent runs outside any FDB transaction."
  [config now]
  (intent-poller/drain-once (runner-config config) now))

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
      (intent-poller/start (runner-config config)))))
