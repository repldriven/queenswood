(ns com.repldriven.queenswood.webhook.outbound
  (:require
    [com.repldriven.queenswood.webhook.core :as core]
    [com.repldriven.queenswood.webhook.domain :as domain]
    [com.repldriven.queenswood.webhook.signing :as signing]
    [com.repldriven.queenswood.webhook.store :as store]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility])
  (:import
    (java.io InputStream)))

(defn- read-bounded
  "At most `domain/max-response-bytes` of `stream`. The rest is
  abandoned unread and the stream closed, so an endpoint answering an
  unbounded body cannot exhaust the runner. Returns how many bytes were
  read."
  [^InputStream stream]
  (if-not stream
    0
    (with-open [in stream]
      (let [buffer (byte-array domain/max-response-bytes)]
        (loop [offset 0]
          (if (>= offset domain/max-response-bytes)
            offset
            (let [n (.read in
                           buffer
                           offset
                           (- domain/max-response-bytes
                              offset))]
              (if (neg? n) offset (recur (+ offset n))))))))))

(defn- post-notification
  "POST the signed body to the tenant's address. Returns `{:status n}`
  for any response, or `{:error message}` when the call failed before
  one arrived, either with `:called? true`.

  Three of the five bounds are here: the request timeout, redirects
  refused, and the response taken as a stream this reads a bounded
  prefix of. The address re-check is the caller's, immediately above;
  the per-endpoint bound is the claim's."
  [config endpoint headers ^bytes body]
  (let [res (http/request {:method :post
                           :url (:address endpoint)
                           :headers (assoc headers
                                           "Content-Type"
                                           "application/json")
                           :body body
                           :as :stream
                           :timeout (:request-timeout-ms config)
                           :follow-redirects false})]
    (if (error/anomaly? res)
      {:error (or (:message (error/payload res)) "webhook request failed")
       :called? true}
      (do (read-bounded (:body res))
          {:status (:status res) :called? true}))))

(defn- attempt-row
  [delivery outcome now duration-ms]
  (utility/assoc-some {:bank-id (:bank-id delivery)
                       :attempt-id (utility/generate-id "wha")
                       :delivery-id (:delivery-id delivery)
                       :attempted-at now
                       :duration-ms duration-ms}
                      :response-status (:status outcome)
                      :error (:error outcome)))

(defn- destination
  [{:keys [bank-id endpoint-id]}]
  (str "webhook-endpoint:" bank-id ":" endpoint-id))

(defn- breaker-policy [config] (get-in config [:delivery-policy :breaker]))

(defn- record-call
  "Record a call's outcome on the endpoint's breaker: a 2xx answered it,
  anything else failed it."
  [config endpoint {:keys [status]} now]
  (let [res (circuit-breaker/record config
                                    (breaker-policy config)
                                    (destination endpoint)
                                    (if (domain/delivered? status)
                                      :answered
                                      :failed)
                                    now)]
    (cond
     (error/anomaly? res)
     (log/error "Circuit breaker not recorded"
                {:destination (destination endpoint) :anomaly res})

     (= :circuit-breaker-status-open (:status res))
     (log/warn "Circuit breaker open; webhook deliveries held"
               {:destination (destination endpoint)
                :next-probe-at (:next-probe-at res)}))))

(defn address-refusal
  "Why the endpoint's address may not be called now, or nil. The host
  is resolved again here and the registration-time rule applied to what
  it answers, so an address whose DNS has moved into a range no tenant
  may be reached on is refused before the request is made. `rule` is
  the deployment's address configuration, the same one registration
  read."
  [address platform-hosts rule]
  (domain/check-address address (core/resolved address) platform-hosts rule))

(defn- outcome-of
  "What the call answered, or why it was never made. An address the
  rule refuses at send time and a signature that cannot be produced are
  both failures of this attempt, recorded as such rather than raised.

  `:address-check` names how an address is checked. The system leaves
  it unset and `address-refusal` applies; a test that starts its own
  receiver sets it, because every address a test can bind to is one the
  rule refuses."
  [config endpoint body message-id now]
  (let [check (or (:address-check config) address-refusal)
        refused (check (:address endpoint)
                       (:platform-hosts config)
                       (:address-rule config))]
    (if refused
      {:error (or (:reason (error/payload refused)) "address refused")}
      (let [headers (signing/headers endpoint message-id body now)]
        (if (error/anomaly? headers)
          {:error "failed to sign delivery"}
          (post-notification config endpoint headers body))))))

(defn deliver-claimed
  "Send one claimed delivery and record what came back, and the call's
  outcome on the endpoint's breaker where the call was made. The call
  sits between two transactions and inside neither: the claim committed
  before it, the outcome commits after it. Returns the delivery as the
  outcome left it, or an anomaly."
  [config delivery]
  (let-nom>
    [endpoint (store/find-endpoint config
                                   (:bank-id delivery)
                                   (:endpoint-id delivery))
     notification (store/find-notification config
                                           (:bank-id delivery)
                                           (:notification-id delivery))]
    (telemetry/with-span-parent
     "webhook-delivery"
     (telemetry/extract-parent-context notification)
     (utility/assoc-some {} "delivery.id" (:delivery-id delivery))
     (fn []
       (let [started (utility/now)
             body (:body notification)
             outcome (outcome-of config
                                 endpoint
                                 body
                                 (:delivery-id delivery)
                                 started)
             now (utility/now)
             updated (domain/record-outcome delivery
                                            outcome
                                            now
                                            (circuit-breaker/retry-policy
                                             (:delivery-policy config)
                                             nil))]
         (let-nom>
           [_ (store/save-outcome config
                                  updated
                                  (attempt-row delivery
                                               outcome
                                               now
                                               (- now started)))]
           (when (:called? outcome)
             (record-call config endpoint outcome now))
           updated))))))

(defn- endpoint-allowance
  "How many of an endpoint's deliveries a pass may claim, asked of its
  breaker in the claim's transaction: none while it is open, one as its
  half-open probe, and the per-endpoint bound while it is closed. A
  breaker that cannot be read lets the bound through."
  [config now]
  (fn [txn bank-id endpoint-id]
    (let [decision (circuit-breaker/allow txn
                                          (breaker-policy config)
                                          (destination {:bank-id bank-id
                                                        :endpoint-id
                                                        endpoint-id})
                                          now)]
      (cond
       (error/anomaly? decision)
       (do (log/error "Circuit breaker not read; delivering as though closed"
                      {:endpoint-id endpoint-id :anomaly decision})
           (:max-in-flight-per-endpoint config))

       (= :open decision)
       0

       (= :probe decision)
       1

       :else
       (:max-in-flight-per-endpoint config)))))

(defn drain-once
  "Claim the deliveries that are due, as many of each endpoint's as its
  breaker allows, and send them. Each claim commits before its call is
  made, so a second replica draining at the same moment finds the rows
  claimed and sends nothing; the batch's calls run alongside each other
  and the pass ends when they have all recorded an outcome."
  [config]
  (let [now (utility/now)
        claimed (store/claim-due-deliveries
                 config
                 {:now now
                  :claimed-by (:runner-id config)
                  :lease-ms (:claim-lease-ms config)
                  :limit (:batch-size config)
                  :per-endpoint-limit (:max-in-flight-per-endpoint config)
                  :endpoint-allowance (endpoint-allowance config now)})]
    (if (error/anomaly? claimed)
      (log/error "Failed to claim due webhook deliveries" {:anomaly claimed})
      (run! deref (mapv #(future (deliver-claimed config %)) claimed)))))

(defn start-runner
  "Start the daemon poll loop that drains due deliveries. Returns
  `{:stop fn}`."
  [config]
  (let [running (atom true)
        poll-ms (:poll-ms config)
        config (update config :runner-id #(or % (str (utility/uuidv7))))
        t (doto
            (Thread.
             (fn []
               (while @running
                 (try (drain-once config)
                      (catch Exception e
                        (log/error e "Webhook drain threw; continuing")))
                 (try (when @running (Thread/sleep poll-ms))
                      (catch InterruptedException _ (reset! running false))))))
            (.setDaemon true)
            (.setName "webhook-outbound-runner")
            (.start))]
    {:stop (fn [] (reset! running false) (.interrupt t))}))
