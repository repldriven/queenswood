(ns com.repldriven.queenswood.clearbank-relay.outbound
  (:require
    [com.repldriven.queenswood.clearbank-relay.store :as store]

    [com.repldriven.queenswood.clearbank-webhook.interface :as
     clearbank-webhook]
    [com.repldriven.queenswood.intent-queue.interface :as intent-queue]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]))

(def ^:private default-poll-ms 200)
(def ^:private default-max-attempts 20)
(def ^:private default-initial-backoff-ms 1000)
(def ^:private default-max-backoff-ms 60000)

(defn- post-signed
  "POST a signed request to the provider. An unreachable provider is
  `:payment/unavailable` — the kind names the domain, not the vendor,
  because a second scheme provider consuming this channel must not
  change what the failure is called (ADR-0020)."
  [url signing-key request-body]
  (error/try-nom
   :payment/unavailable
   "Failed to POST outbound payment to the scheme adapter"
   (let [res (let-nom> [signature (clearbank-webhook/sign
                                   (:private-key signing-key)
                                   request-body)]
               (http/request {:method :post
                              :url url
                              :headers {"Content-Type" "application/json"
                                        clearbank-webhook/signature-header
                                        signature}
                              :body request-body}))]
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
  [config now intent attempts failure-kind reason]
  (let [{:keys [schemas]} config
        {:keys [intent-id dedup-key]} intent]
    (let-nom>
      [payload (avro/serialize (get schemas "transaction-rejected")
                               {:end-to-end-id dedup-key
                                :scheme "fps"
                                :debit-credit-code :debit-credit-code-debit
                                :cancellation-code "NARR"
                                :failure-kind failure-kind
                                :reason-code "NARR"
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

(defn- relay-payment
  "Make the outbound FPS call for one intent OUTSIDE any FDB
  transaction, then record one of four outcomes: an accepted submission
  marks the intent sent, a refusal fails it immediately, a transport
  failure at the attempt cap fails it too, and any earlier transport
  failure parks it for another attempt one backoff step further out. A
  retried POST is safe — ClearBank dedupes on endToEndIdentification —
  which is what lets the schedule retry at all."
  [config now intent]
  (let [{:keys [clearbank-url signing-key max-attempts post-fn]} config
        {:keys [intent-id dedup-key request]} intent
        post (or post-fn post-signed)
        max-attempts (or max-attempts default-max-attempts)
        attempts (inc (or (:attempts intent) 0))
        [outcome reason] (classify (post (str clearbank-url "/v3/payments/fps")
                                         signing-key
                                         request)
                                   dedup-key)]
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
                      :failure-kind-refused
                      reason))

     (>= attempts max-attempts)
     (do (log/error "Outbound intent giving up after max attempts"
                    {:intent-id intent-id :attempts attempts :reason reason})
         (fail-intent config
                      now
                      intent
                      attempts
                      :failure-kind-undelivered
                      reason))

     :else
     (do (log/warn "Outbound intent POST failed; will retry"
                   {:intent-id intent-id :attempt attempts :reason reason})
         (store/mark-attempt config
                             intent-id
                             attempts
                             (+ now (backoff-ms config attempts)))))))

(def ^:private account-calls
  {"open-account" {:path (fn [_] "/v1/virtual-accounts")
                   :opened "payment-account-opened"
                   :refused "payment-account-refused"}
   "close-account"
   {:path (fn [{:keys [provider-account-id]}]
            (str "/v1/virtual-accounts/" provider-account-id "/close"))
    :opened "payment-account-closed"
    :refused "payment-account-close-refused"}
   "reissue-address"
   {:path (fn [{:keys [provider-account-id]}]
            (if provider-account-id
              (str "/v1/virtual-accounts/" provider-account-id "/reissue")
              "/v1/virtual-accounts"))
    :opened "payment-address-reissued"
    :refused "payment-address-reissue-failed"}})

(def ^:private refusals
  "The events that end an account call failed rather than complete."
  #{"payment-account-refused" "payment-account-close-refused"
    "payment-address-reissue-failed"})

(defn- classify-account-call
  [res]
  (let [status (:status res)]
    (cond
     (error/anomaly? res)
     [:retry (:message (error/payload res))]

     (not (int? status))
     [:retry "no HTTP status"]

     (or (<= 500 status) (= 408 status) (= 429 status))
     [:retry (str "HTTP " status)]

     (<= 400 status 499)
     [:refused (or (:detail (http/res->edn res)) (str "HTTP " status))]

     (<= 200 status 299)
     [:sent (http/res->edn res)]

     :else
     [:retry (str "HTTP " status)])))

(defn- event-data
  [event-name context body]
  (let [{:keys [bank-id account-id rotation-key]} context
        {:keys [id sortCode accountNumber]} body]
    (case event-name
      "payment-account-opened"
      {:bank-id bank-id
       :account-id account-id
       :provider-account-id id
       :addresses [{:scheme "scan"
                    :sort-code sortCode
                    :account-number accountNumber}]}

      "payment-address-reissued"
      {:bank-id bank-id
       :account-id account-id
       :provider-account-id id
       :rotation-key rotation-key
       :addresses [{:scheme "scan"
                    :sort-code sortCode
                    :account-number accountNumber}]}

      "payment-account-closed"
      {:bank-id bank-id :account-id account-id}

      "payment-account-refused"
      {:bank-id bank-id :account-id account-id :reason body}

      "payment-account-close-refused"
      {:bank-id bank-id :account-id account-id :reason body}

      "payment-address-reissue-failed"
      {:bank-id bank-id
       :account-id account-id
       :rotation-key rotation-key
       :reason body})))

(defn- account-event
  [config now intent event-name data]
  (let [{:keys [schemas]} config
        {:keys [intent-id dedup-key traceparent]} intent]
    (let-nom> [payload (avro/serialize (get schemas event-name) data)]
      (utility/assoc-some {:outbox-id (str (utility/uuidv7))
                           :dedup-key (str dedup-key ":" event-name)
                           :event-name event-name
                           :payload payload
                           :correlation-id (str (utility/uuidv7))
                           :causation-id intent-id
                           :created-at now}
                          :traceparent
                          traceparent))))

(defn- finish-account-call
  [config now intent attempts event-name body]
  (let [{:keys [intent-id context]} intent
        context (edn/read-string context)]
    (if (nil? event-name)
      (store/fail-intent config intent-id attempts nil)
      (let-nom> [event (account-event config
                                      now
                                      intent
                                      event-name
                                      (event-data event-name context body))]
        (if (contains? refusals event-name)
          (store/fail-intent config intent-id attempts event)
          (store/complete-intent config intent-id event))))))

(defn- relay-account-call
  [config now intent]
  (let [{:keys [clearbank-url signing-key max-attempts post-fn]} config
        {:keys [intent-id kind request context]} intent
        {:keys [path opened refused]} (get account-calls kind)
        post (or post-fn post-signed)
        max-attempts (or max-attempts default-max-attempts)
        attempts (inc (or (:attempts intent) 0))
        [outcome result] (classify-account-call
                          (post (str clearbank-url
                                     (path (edn/read-string context)))
                                signing-key
                                request))]
    (cond
     (= :sent outcome)
     (finish-account-call config now intent attempts opened result)

     (= :refused outcome)
     (do (log/error "Account call refused"
                    {:intent-id intent-id :kind kind :reason result})
         (finish-account-call config now intent attempts refused result))

     (>= attempts max-attempts)
     (do (log/error "Account call giving up after max attempts"
                    {:intent-id intent-id :kind kind :reason result})
         (finish-account-call config
                              now
                              intent
                              attempts
                              refused
                              (str "Undelivered: " result)))

     :else
     (do (log/warn "Account call failed; will retry"
                   {:intent-id intent-id :kind kind :attempt attempts})
         (store/mark-attempt config
                             intent-id
                             attempts
                             (+ now (backoff-ms config attempts)))))))

(defn- relay-one
  [config now intent]
  (if (contains? account-calls (:kind intent))
    (relay-account-call config now intent)
    (relay-payment config now intent)))

(defn- in-intent-trace
  [span-name intent f]
  (telemetry/with-span-parent span-name
                              (telemetry/extract-parent-context intent)
                              (utility/assoc-some {}
                                                  "intent.id"
                                                  (:intent-id intent)
                                                  "intent.kind"
                                                  (:kind intent))
                              f))

(defn drain-once
  "Relay each pending intent whose `next-attempt-at` is not after `now`
  once, oldest first, holding a call for an account while an earlier one
  for it is unsent. Reads are transactional; the HTTP call and status
  write per intent are separate, so no network I/O happens inside an FDB
  transaction."
  [config now]
  (let [pending (store/pending-intents config)]
    (when-not (error/anomaly? pending)
      (intent-queue/drain pending
                          now
                          {:settles-first? (constantly false)
                           :run (fn [i]
                                  (in-intent-trace "clearbank-outbound"
                                                   i
                                                   (fn []
                                                     (relay-one config
                                                                now
                                                                i))))}))))

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
