(ns com.repldriven.queenswood.onfido-relay.outbound
  (:require
    [com.repldriven.queenswood.onfido-relay.store :as store]

    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]
    [clojure.set :as set]
    [clojure.string :as str])
  (:import
    (java.net URLEncoder)
    (java.nio.charset StandardCharsets)
    (java.time Instant)))

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

(defn- split-name
  "An applicant's first and last names from a legal name, split at its
  last space: Onfido takes them apart, and its report is the only use it
  makes of them."
  [legal-name]
  (let [words (str/split (str/trim legal-name) #"\s+")]
    (if (next words)
      [(str/join " " (butlast words)) (last words)]
      [legal-name legal-name])))

(defn applicant
  [data]
  (let [{:keys! [legal-name] :keys [email]} data
        [first-name last-name] (split-name legal-name)]
    (utility/assoc-some {:first_name first-name
                         :last_name last-name}
                        :email
                        (when-not (str/blank? email) email))))

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

(defn- request
  [intent]
  (edn/read-string (:request intent)))

(defn- check
  [config _now intent]
  (let [data (request intent)
        criteria (concat (:verifications data) (:screenings data))
        workflow (select-workflow (:workflows config) criteria)
        res (if workflow
              (run-for config workflow data)
              (error/fail :idv/unsupported-criteria
                          {:message "No configured workflow covers the request"
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
  [config _now intent run]
  (let [data (request intent)]
    {:status :outbound-intent-status-settled
     :event (when (:session-id data) (session-opened config data run))}))

(defn- check-failed
  [_config _now intent failure reason]
  (let [data (request intent)]
    {:status :outbound-intent-status-failed
     :event (when (:session-id data)
              (session-failed data
                              (if (= :undelivered failure)
                                (str "Undelivered: " reason)
                                reason)))}))

(intent-poller/defoperations
 :onfido
 {:onfido-outbound-intent-kind-check
  {:call check :answered checked :failed check-failed}})

(defn- runner-config
  [config]
  (assoc config
         :adapter :onfido
         :store store/spec))

(defn drain-once
  [config now]
  (intent-poller/drain-once (runner-config config) now))

(defn start-runner
  [config]
  (let [missing (uncovered (:workflows config) (:idv-provider config))]
    (if (seq missing)
      (error/fail :idv/unsupported-criteria
                  {:message (str "No configured workflow establishes "
                                 (str/join ", " (sort missing)))
                   :missing missing})
      (intent-poller/start (runner-config config)))))

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
