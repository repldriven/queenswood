(ns com.repldriven.queenswood.onfido-relay.outbound
  "The outbound Onfido relay: makes the create-applicant + create-check
  call pair for each pending intent, OUTSIDE any FDB transaction. The
  `:verification-id` is smuggled to Onfido as the check `:external_id` so
  the webhook can correlate the result back."
  (:require
    [com.repldriven.queenswood.onfido-relay.store :as store]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]

    [clojure.edn :as edn]
    [clojure.string :as str]))

(def ^:private default-poll-ms 200)
(def ^:private default-max-attempts 10)

(defn composite-external-id
  "Pack `:bank-id` and `:verification-id` into Onfido's single opaque
  correlation field, separated by `|`."
  [bank-id verification-id]
  (str bank-id "|" verification-id))

(defn parse-external-id
  "Inverse of `composite-external-id`. Returns
  `{:bank-id ... :verification-id ...}` or nil."
  [s]
  (when (and s (.contains ^String s "|"))
    (let [[bnk vid] (.split ^String s "\\|" 2)]
      {:bank-id bnk :verification-id vid})))

(defn- classify
  "Turn a provider response into itself or the anomaly that names what
  went wrong. Kinds stay in the `:idv/*` namespace rather than naming
  the vendor — they surface as the API's RFC 9457 `type`, and a second
  identity provider consuming this channel must not change the contract
  (ADR-0020). An unreachable provider, a 5xx and a 429 are retryable and
  say so, a remaining 4xx means our request is wrong and keeps the
  call-site name.

  Separate from the call so it can be tested as the pure function it is
  — stubbing the HTTP layer would mean a global redef, which is not safe
  alongside a parallel test suite."
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

(defn- post
  [url body]
  (error/try-nom
   :idv/unavailable
   "Identity verification provider call failed"
   (classify url
             (http/request {:method :post
                            :url url
                            :headers {"Content-Type" "application/json"}
                            :body (json/write-str body)}))))

(defn- full-first-name
  [first-name middle-names]
  (if (str/blank? middle-names)
    first-name
    (str/trim (str first-name " " middle-names))))

(defn- address->onfido
  [{:keys [flat-number building-number building-name street sub-street
           town state postcode country start-date]}]
  (cond-> {:street street
           :town town
           :postcode postcode
           :country country}
          flat-number
          (assoc :flat_number flat-number)

          building-number
          (assoc :building_number building-number)

          building-name
          (assoc :building_name building-name)

          sub-street
          (assoc :sub_street sub-street)

          state
          (assoc :state state)

          start-date
          (assoc :start_date start-date)))

(defn- create-applicant
  [onfido-url {:keys [first-name middle-names last-name date-of-birth address]}]
  (post (str onfido-url "/v3.6/applicants")
        (cond-> {:first_name (full-first-name first-name middle-names)
                 :last_name last-name
                 :address (address->onfido address)}
                date-of-birth
                (assoc :dob date-of-birth))))

(defn- create-check
  [onfido-url applicant-id bank-id verification-id]
  (post (str onfido-url "/v3.6/checks")
        {:applicant_id applicant-id
         :report_names ["document" "facial_similarity_photo"]
         :external_id (composite-external-id bank-id verification-id)}))

(defn- submit-idv-check
  "The create-applicant + create-check pair. Returns the check response
  or an anomaly."
  [onfido-url {:keys [bank-id verification-id] :as data}]
  (let [applicant (create-applicant onfido-url data)]
    (if (error/anomaly? applicant)
      applicant
      (create-check onfido-url
                    (:id (http/res->edn applicant))
                    bank-id
                    verification-id))))

(defn- relay-one
  [config {:keys [intent-id request attempts]}]
  (let [{:keys [onfido-url max-attempts]} config
        max-attempts (or max-attempts default-max-attempts)
        res (submit-idv-check onfido-url (edn/read-string request))
        next-attempts (inc (or attempts 0))]
    (cond
     (not (error/anomaly? res))
     (store/mark-sent config intent-id)

     (>= next-attempts max-attempts)
     (do (log/error "Onfido intent giving up after max attempts"
                    {:intent-id intent-id :attempts next-attempts :last res})
         (store/mark-failed config intent-id next-attempts))

     :else
     (do (log/warn "Onfido intent submit failed; will retry"
                   {:intent-id intent-id :attempt next-attempts})
         (store/mark-attempt config intent-id next-attempts)))))

(defn drain-once
  "Relay every pending intent once. The Onfido calls per intent run
  outside any FDB transaction."
  [config]
  (let [pending (store/pending-intents config)]
    (when-not (error/anomaly? pending)
      (doseq [i pending] (relay-one config i)))))

(defn start-runner
  "Start the daemon poll loop that drains pending outbound intents.
  Returns `{:stop fn}`."
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
