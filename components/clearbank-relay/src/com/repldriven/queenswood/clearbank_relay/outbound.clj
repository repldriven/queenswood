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

(defn- context
  [intent]
  (or (some-> (not-empty (:context intent))
              edn/read-string)
      {}))

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

(defn- post
  [config path request]
  (let [{:keys [clearbank-url signing-key post-fn]} config]
    ((or post-fn post-signed) (str clearbank-url path) signing-key request)))

(defn- give-up?
  [config attempts]
  (>= attempts (or (:max-attempts config) default-max-attempts)))

(defn- retry
  [config now intent attempts reason]
  (let [{:keys [intent-id kind]} intent]
    (log/warn
     "ClearBank call failed; will retry"
     {:intent-id intent-id :kind kind :attempt attempts :reason reason})
    (store/mark-attempt config
                        intent-id
                        attempts
                        (+ now (backoff-ms config attempts)))))

;; ---- payments

(defn- rejected
  [intent failure-kind reason now]
  {:event-name "transaction-rejected"
   :dedup-key (str (:dedup-key intent) ":submission-rejected")
   :data {:end-to-end-id (:dedup-key intent)
          :scheme "fps"
          :debit-credit-code :debit-credit-code-debit
          :cancellation-code "NARR"
          :failure-kind failure-kind
          :reason-code "NARR"
          :cancellation-reason reason
          :is-return false
          :timestamp-rejected now}})

(defn- relay-payment
  "Make the outbound FPS call for one intent OUTSIDE any FDB
  transaction, then record one of four outcomes: an accepted submission
  marks the intent sent, a refusal fails it immediately, a transport
  failure at the attempt cap fails it too, and any earlier transport
  failure parks it for another attempt one backoff step further out. A
  retried POST is safe — ClearBank dedupes on endToEndIdentification —
  which is what lets the schedule retry at all."
  [config now intent]
  (let [{:keys [intent-id dedup-key request]} intent
        attempts (inc (or (:attempts intent) 0))
        intent (assoc intent :attempts attempts)
        [outcome reason] (classify (post config "/v3/payments/fps" request)
                                   dedup-key)]
    (cond
     (= :sent outcome)
     (store/mark-sent config intent-id)

     (= :refused outcome)
     (do (log/error "ClearBank refused the payment"
                    {:intent-id intent-id :reason reason})
         (finish config
                 now
                 intent
                 "failed"
                 (rejected intent :failure-kind-refused reason now)))

     (give-up? config attempts)
     (do (log/error "ClearBank call giving up after max attempts"
                    {:intent-id intent-id :reason reason})
         (finish config
                 now
                 intent
                 "failed"
                 (rejected intent :failure-kind-undelivered reason now)))

     :else
     (retry config now intent attempts reason))))

;; ---- accounts

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

(defn- account-event
  [intent event-name data]
  {:event-name event-name
   :dedup-key (str (:dedup-key intent) ":" event-name)
   :data data})

(defn- addresses
  [body]
  (let [{:keys [sortCode accountNumber]} body]
    [{:scheme "scan" :sort-code sortCode :account-number accountNumber}]))

(defn- relay-open
  [config now intent]
  (let [{:keys [intent-id request]} intent
        {:keys! [bank-id account-id]} (context intent)
        attempts (inc (or (:attempts intent) 0))
        intent (assoc intent :attempts attempts)
        [outcome result] (classify-account-call
                          (post config "/v1/virtual-accounts" request))
        refused (fn [reason]
                  (account-event intent
                                 "payment-account-refused"
                                 {:bank-id bank-id
                                  :account-id account-id
                                  :reason reason}))]
    (cond
     (= :sent outcome)
     (finish config
             now
             intent
             "settled"
             (account-event intent
                            "payment-account-opened"
                            {:bank-id bank-id
                             :account-id account-id
                             :provider-account-id (:id result)
                             :addresses (addresses result)}))

     (= :refused outcome)
     (do (log/error "ClearBank refused an account opening"
                    {:intent-id intent-id :reason result})
         (finish config now intent "failed" (refused result)))

     (give-up? config attempts)
     (finish config now intent "failed" (refused (str "Undelivered: " result)))

     :else
     (retry config now intent attempts result))))

(defn- relay-close
  [config now intent]
  (let [{:keys [intent-id request]} intent
        {:keys! [bank-id account-id provider-account-id]} (context intent)
        attempts (inc (or (:attempts intent) 0))
        intent (assoc intent :attempts attempts)
        [outcome result] (classify-account-call
                          (post config
                                (str "/v1/virtual-accounts/"
                                     provider-account-id
                                     "/close")
                                request))
        close-refused (fn [reason]
                        (account-event intent
                                       "payment-account-close-refused"
                                       {:bank-id bank-id
                                        :account-id account-id
                                        :reason reason}))]
    (cond
     (= :sent outcome)
     (finish config
             now
             intent
             "settled"
             (account-event intent
                            "payment-account-closed"
                            {:bank-id bank-id :account-id account-id}))

     (= :refused outcome)
     (do (log/error
          "ClearBank refused to close the account"
          {:intent-id intent-id :account-id account-id :reason result})
         (finish config now intent "failed" (close-refused result)))

     (give-up? config attempts)
     (finish config
             now
             intent
             "failed"
             (close-refused (str "Undelivered: " result)))

     :else
     (retry config now intent attempts result))))

(defn- relay-reissue
  [config now intent]
  (let [{:keys [intent-id request]} intent
        {:keys! [bank-id account-id rotation-key] :keys [provider-account-id]}
        (context intent)
        attempts (inc (or (:attempts intent) 0))
        intent (assoc intent :attempts attempts)
        path (if provider-account-id
               (str "/v1/virtual-accounts/" provider-account-id "/reissue")
               "/v1/virtual-accounts")
        [outcome result] (classify-account-call (post config path request))
        reissue-failed (fn [reason]
                         (account-event intent
                                        "payment-address-reissue-failed"
                                        {:bank-id bank-id
                                         :account-id account-id
                                         :rotation-key rotation-key
                                         :reason reason}))]
    (cond
     (= :sent outcome)
     (finish config
             now
             intent
             "settled"
             (account-event intent
                            "payment-address-reissued"
                            {:bank-id bank-id
                             :account-id account-id
                             :provider-account-id (:id result)
                             :rotation-key rotation-key
                             :addresses (addresses result)}))

     (= :refused outcome)
     (do (log/error "ClearBank refused an address reissue"
                    {:intent-id intent-id :reason result})
         (finish config now intent "failed" (reissue-failed result)))

     (give-up? config attempts)
     (finish config
             now
             intent
             "failed"
             (reissue-failed (str "Undelivered: " result)))

     :else
     (retry config now intent attempts result))))

(def ^:private relays
  {"open-account" relay-open
   "close-account" relay-close
   "reissue-address" relay-reissue})

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

(defn- checked
  "Run `f` on `intent`, failing the intent where its call throws, so it no
  longer holds the intents behind it."
  [config intent f]
  (let [res (error/try-nom-ex :clearbank-relay/intent
                              Exception
                              "ClearBank intent could not be relayed"
                              (f intent))]
    (if (error/anomaly? res)
      (let [{:keys [intent-id kind status attempts]} intent]
        (log/error "ClearBank intent could not be relayed; failing it"
                   {:intent-id intent-id :kind kind :anomaly res})
        (store/finish config intent-id status "failed" attempts nil)
        (assoc intent :status "failed"))
      res)))

(defn drain-once
  "Relay each pending intent whose `next-attempt-at` is not after `now`
  once, oldest first, holding a call for an account while an earlier one
  for it is unsent. Reads are transactional; the HTTP call and status
  write per intent are separate, so no network I/O happens inside an FDB
  transaction."
  [config now]
  (let [pending (store/intents-with-status config "pending")]
    (when-not (error/anomaly? pending)
      (intent-queue/drain
       pending
       now
       {:settles-first? (constantly false)
        :run
        (fn [i]
          (in-intent-trace
           "clearbank-outbound"
           i
           (fn []
             (checked
              config
              i
              (fn [i]
                ((get relays (:kind i) relay-payment) config now i))))))}))))

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
