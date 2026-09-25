(ns com.repldriven.queenswood.zyphe-relay.outbound
  "The outbound Zyphe relay: creates a verification request for each
  pending intent, OUTSIDE any FDB transaction. The request carries the
  bank and verification ids as `customData`, which Zyphe echoes on every
  webhook as `flow.customData`, and installs a session webhook that
  delivers the run's events, signed with the adapter's secret, to the
  adapter."
  (:require
    [com.repldriven.queenswood.zyphe-relay.store :as store]

    [com.repldriven.queenswood.zyphe-webhook.interface :as zyphe-webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]

    [clojure.edn :as edn]))

(def ^:private default-poll-ms 200)
(def ^:private default-max-attempts 10)

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

(defn create-url
  "Zyphe's create-or-resume verification request endpoint for the
  configured flow, in sandbox or production."
  [{:keys [zyphe-url flow-id sandbox]}]
  (str zyphe-url "/sdk/flow/" flow-id "/vr/create?sandbox=" sandbox))

(defn verification-request
  "The create-verification-request body for a submit-idv-check. The
  person is identified by party id as an external-id credential, the
  bank and verification ids ride as `customData`, and the session
  webhook is keyed by the adapter's secret. Creating again for the same
  identity resumes the run, so a retried intent does not start a second
  one."
  [{:keys [adapter-url webhook-secret]}
   {:keys [bank-id verification-id
           party-id]}]
  {:credentials [{:type "EXTERNAL_ID" :externalId party-id}]
   :customData {:bankId bank-id :verificationId verification-id}
   :webhook {:url (str adapter-url zyphe-webhook/path)
             :secret webhook-secret
             :payloadVersion "V2"}})

(defn- submit-idv-check
  [config data]
  (let [url (create-url config)]
    (error/try-nom
     :idv/unavailable
     "Identity verification provider call failed"
     (classify url
               (http/request {:method :post
                              :url url
                              :headers {"Content-Type" "application/json"
                                        "x-api-key" (:api-key config)}
                              :body (json/write-str
                                     (verification-request config data))})))))

(defn- relay-one
  [config {:keys [intent-id request attempts]}]
  (let [{:keys [max-attempts]} config
        max-attempts (or max-attempts default-max-attempts)
        res (submit-idv-check config (edn/read-string request))
        next-attempts (inc (or attempts 0))]
    (cond
     (not (error/anomaly? res))
     (store/mark-sent config intent-id)

     (>= next-attempts max-attempts)
     (do (log/error "Zyphe intent giving up after max attempts"
                    {:intent-id intent-id :attempts next-attempts :last res})
         (store/mark-failed config intent-id next-attempts))

     :else
     (do (log/warn "Zyphe intent submit failed; will retry"
                   {:intent-id intent-id :attempt next-attempts})
         (store/mark-attempt config intent-id next-attempts)))))

(defn drain-once
  "Relay every pending intent once. The Zyphe call per intent runs
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
                                       "Zyphe relay drain threw; continuing")))
                     (try (when @running (Thread/sleep poll-ms))
                          (catch InterruptedException _
                            (reset! running false))))))
            (.setDaemon true)
            (.setName "zyphe-outbound-relay")
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t))}))
