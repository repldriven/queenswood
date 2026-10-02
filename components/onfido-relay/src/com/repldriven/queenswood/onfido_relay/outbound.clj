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

(defn- session-opened
  [config data run]
  (let [{:keys [schemas]} config
        {:keys! [bank-id verification-id session-id]} data
        {:keys [url expires_at]} (:link run)]
    (let-nom> [payload (avro/serialize
                        (get schemas "idv-session-opened")
                        {:bank-id bank-id
                         :verification-id verification-id
                         :session-id session-id
                         :url url
                         :expires-at (if expires_at
                                       (.toEpochMilli (Instant/parse
                                                       expires_at))
                                       (expires-at config))})]
      {:outbox-id (str (utility/uuidv7))
       :dedup-key (str session-id ":opened")
       :event-name "idv-session-opened"
       :payload payload
       :correlation-id (str (utility/uuidv7))
       :causation-id session-id
       :created-at (utility/now)})))

(defn- record-opened
  [config intent-id data run]
  (if (nil? (:session-id data))
    (store/mark-sent config intent-id)
    (let-nom> [event (session-opened config data run)]
      (store/transact config
                      (fn [txn]
                        (let [saved (store/save-event txn event)]
                          (if (and (error/anomaly? saved)
                                   (not (store/uniqueness-violation? saved)))
                            saved
                            (store/mark-sent txn intent-id))))
                      :onfido-outbound/opened
                      "Failed to record the opened session"))))

(defn- session-failed
  [config data reason]
  (let [{:keys [schemas]} config
        {:keys! [bank-id verification-id session-id]} data]
    (let-nom> [payload (avro/serialize (get schemas "idv-session-failed")
                                       {:bank-id bank-id
                                        :verification-id verification-id
                                        :session-id session-id
                                        :reason reason})]
      {:outbox-id (str (utility/uuidv7))
       :dedup-key (str session-id ":failed")
       :event-name "idv-session-failed"
       :payload payload
       :correlation-id (str (utility/uuidv7))
       :causation-id session-id
       :created-at (utility/now)})))

(defn- record-failed
  [config intent-id attempts data reason]
  (if (nil? (:session-id data))
    (store/mark-failed config intent-id attempts)
    (let-nom> [event (session-failed config data reason)]
      (store/transact config
                      (fn [txn]
                        (let [saved (store/save-event txn event)]
                          (if (and (error/anomaly? saved)
                                   (not (store/uniqueness-violation? saved)))
                            saved
                            (store/mark-failed txn intent-id attempts))))
                      :onfido-outbound/failed
                      "Failed to record the failed session"))))

(defn- refused?
  "True for a failure retrying cannot mend: the provider refused the
  request, or no configured workflow covers it."
  [res]
  (contains? #{:idv/http :idv/unsupported-criteria} (error/kind res)))

(defn- relay-one
  "Make `intent`'s call, returning the intent with the status it was left
  at, or an anomaly."
  [config {:keys [intent-id request attempts] :as intent}]
  (let [{:keys [max-attempts workflows]} config
        max-attempts (or max-attempts default-max-attempts)
        data (edn/read-string request)
        workflow (select-workflow workflows
                                  (concat (:verifications data)
                                          (:screenings data)))
        res (if workflow
              (run-for config workflow data)
              (error/fail :idv/unsupported-criteria
                          {:message "No configured workflow covers the request"
                           :verifications (:verifications data)
                           :screenings (:screenings data)}))
        next-attempts (inc (or attempts 0))]
    (cond
     (not (error/anomaly? res))
     (let-nom> [_ (record-opened config intent-id data res)]
       (assoc intent :status "sent"))

     (or (refused? res) (>= next-attempts max-attempts))
     (do (log/error "Onfido intent failed"
                    {:intent-id intent-id :attempts next-attempts :last res})
         (let-nom> [_ (record-failed config
                                     intent-id
                                     next-attempts
                                     data
                                     (if (refused? res)
                                       (:message (error/payload res))
                                       (str "Undelivered: "
                                            (:message (error/payload res)))))]
           (assoc intent :status "failed")))

     :else
     (do (log/warn "Onfido intent submit failed; will retry"
                   {:intent-id intent-id :attempt next-attempts})
         (let-nom> [_ (store/mark-attempt config intent-id next-attempts)]
           (assoc intent :attempts next-attempts))))))

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
      (let [{:keys [intent-id attempts]} intent]
        (log/error "Onfido intent could not be relayed; failing it"
                   {:intent-id intent-id :anomaly res})
        (store/mark-failed config intent-id (or attempts 0))
        (assoc intent :status "failed"))
      res)))

(defn drain-once
  [config]
  (let [pending (store/pending-intents config)]
    (when-not (error/anomaly? pending)
      (intent-queue/drain pending
                          (utility/now)
                          {:settles-first? (constantly false)
                           :run (fn [i]
                                  (in-intent-trace "onfido-outbound"
                                                   i
                                                   (fn []
                                                     (checked
                                                      config
                                                      i
                                                      (fn [i]
                                                        (relay-one config
                                                                   i))))))}))))

(defn- start-loop
  [config]
  (let [running (atom true)
        poll-ms (or (:poll-ms config) default-poll-ms)
        t (doto (Thread.
                 (fn []
                   (while @running
                     (try (drain-once config)
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
