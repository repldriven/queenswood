(ns com.repldriven.queenswood.clearbank-relay.outbound
  (:require
    [com.repldriven.queenswood.clearbank-relay.store :as store]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private default-poll-ms 200)
(def ^:private default-max-attempts 20)
(def ^:private default-initial-backoff-ms 1000)
(def ^:private default-max-backoff-ms 60000)

(defn- post-fps
  "POST the outbound payment to the scheme adapter. An unreachable
  adapter is `:payment/unavailable` — the kind names the domain, not the
  vendor, because a second scheme provider consuming this channel must
  not change what the failure is called (ADR-0020)."
  [clearbank-url request-body]
  (error/try-nom
   :payment/unavailable
   "Failed to POST outbound payment to the scheme adapter"
   (let [res (http/request {:method :post
                            :url (str clearbank-url "/v3/payments/fps")
                            :headers {"Content-Type" "application/json"}
                            :body request-body})]
     (if (error/anomaly? res)
       (error/fail :payment/unavailable
                   {:message "Scheme adapter unreachable"
                    :cause res})
       res))))

(defn- transaction-response
  [res end-to-end-id]
  (let [body (http/res->edn res)]
    (when (map? body)
      (some (fn [{:keys [endToEndIdentification response]}]
              (when (= end-to-end-id endToEndIdentification) response))
            (:transactions body)))))

(defn- classify
  [res end-to-end-id]
  (let [status (:status res)]
    (cond
     (error/anomaly? res)
     [:retry (:message (error/payload res))]

     (not (int? status))
     [:retry "no HTTP status"]

     (or (<= 500 status) (= 408 status) (= 429 status))
     [:retry (str "HTTP " status)]

     (<= 400 status 499)
     [:refused (str "HTTP " status)]

     (<= 200 status 299)
     (let [response (transaction-response res end-to-end-id)]
       (cond
        (= "Accepted" response)
        [:sent nil]

        (some? response)
        [:refused (str "HTTP " status " response " response)]

        :else
        [:refused
         (str "HTTP " status
              " with no response for "
              end-to-end-id)]))

     :else
     [:retry (str "HTTP " status)])))

(defn- backoff-ms
  [config attempts]
  (let [{:keys [initial-backoff-ms max-backoff-ms]} config
        initial (or initial-backoff-ms default-initial-backoff-ms)
        cap (or max-backoff-ms default-max-backoff-ms)]
    (reduce (fn [delay _] (min cap (* 2 delay)))
            (min cap initial)
            (range (dec attempts)))))

(defn- fail-intent
  [config now intent attempts cancellation-code reason]
  (let [{:keys [schemas]} config
        {:keys [intent-id dedup-key]} intent]
    (let-nom>
      [payload (avro/serialize (get schemas "transaction-rejected")
                               {:end-to-end-id dedup-key
                                :scheme "FasterPayments"
                                :debit-credit-code :debit-credit-code-debit
                                :cancellation-code cancellation-code
                                :cancellation-reason reason
                                :is-return false
                                :timestamp-rejected now})]
      (store/fail-intent config
                         intent-id
                         attempts
                         {:outbox-id (str (utility/uuidv7))
                          :dedup-key (str dedup-key ":submission-rejected")
                          :event-name "transaction-rejected"
                          :payload payload
                          :correlation-id (str (utility/uuidv7))
                          :causation-id intent-id
                          :created-at now}))))

(defn- relay-one
  "Make the outbound FPS call for one intent OUTSIDE any FDB
  transaction, then record one of four outcomes: an accepted submission
  marks the intent sent, a refusal fails it immediately, a transport
  failure at the attempt cap fails it too, and any earlier transport
  failure parks it for another attempt one backoff step further out. A
  retried POST is safe — ClearBank dedupes on endToEndIdentification —
  which is what lets the schedule retry at all."
  [config now intent]
  (let [{:keys [clearbank-url max-attempts post-fn]} config
        {:keys [intent-id dedup-key request]} intent
        post (or post-fn post-fps)
        max-attempts (or max-attempts default-max-attempts)
        attempts (inc (or (:attempts intent) 0))
        [outcome reason] (classify (post clearbank-url request) dedup-key)]
    (cond
     (= :sent outcome)
     (store/mark-sent config intent-id)

     (= :refused outcome)
     (do (log/error "Outbound intent refused"
                    {:intent-id intent-id :attempts attempts :reason reason})
         (fail-intent config
                      now
                      intent
                      attempts
                      "CB_SubmissionRefused"
                      reason))

     (>= attempts max-attempts)
     (do (log/error "Outbound intent giving up after max attempts"
                    {:intent-id intent-id :attempts attempts :reason reason})
         (fail-intent config
                      now
                      intent
                      attempts
                      "CB_SubmissionFailed"
                      reason))

     :else
     (do (log/warn "Outbound intent POST failed; will retry"
                   {:intent-id intent-id :attempt attempts :reason reason})
         (store/mark-attempt config
                             intent-id
                             attempts
                             (+ now (backoff-ms config attempts)))))))

(defn drain-once
  "Relay every pending intent whose `next-attempt-at` is not after `now`
  once. Reads are transactional; the HTTP call and status write per
  intent are separate, so no network I/O happens inside an FDB
  transaction."
  [config now]
  (let [pending (store/pending-intents config)]
    (when-not (error/anomaly? pending)
      (doseq [i pending
              :when (<= (or (:next-attempt-at i) 0) now)]
        (relay-one config now i)))))

(defn start-runner
  "Start the daemon poll loop that drains pending outbound intents.
  Returns `{:stop fn}`."
  [config]
  (let [running (atom true)
        poll-ms (or (:poll-ms config) default-poll-ms)
        t (doto
            (Thread.
             (fn []
               (while @running
                 (try (drain-once config (utility/now))
                      (catch Exception e
                        (log/error e "Outbound relay drain threw; continuing")))
                 (try (when @running (Thread/sleep poll-ms))
                      (catch InterruptedException _ (reset! running false))))))
            (.setDaemon true)
            (.setName "clearbank-outbound-relay")
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t))}))
