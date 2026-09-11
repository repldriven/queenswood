(ns com.repldriven.queenswood.webhook.outbound
  (:require
    [com.repldriven.queenswood.webhook.core :as core]
    [com.repldriven.queenswood.webhook.domain :as domain]
    [com.repldriven.queenswood.webhook.signing :as signing]
    [com.repldriven.queenswood.webhook.store :as store]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str])
  (:import
    (java.io InputStream)
    (java.net InetAddress)))

(def ^:private default-poll-ms 200)

(def ^:private default-batch-size
  "How many due deliveries one pass claims. The per-endpoint bound is
  what keeps one tenant from taking them all."
  32)

(defn- resolved
  "The host's addresses as the platform sees them now. Re-resolved
  immediately before the request, so an address whose DNS has moved
  into a range no tenant may be reached on is refused at send time as
  it would have been at registration."
  [address]
  (let [host (some-> address
                     (str/replace #"^[a-zA-Z]+://" "")
                     (str/split #"[/:?#]" 2)
                     first)
        result (error/try-nom
                :webhook-endpoint/resolve
                "Failed to resolve webhook address"
                (mapv #(.getHostAddress ^InetAddress %)
                      (InetAddress/getAllByName host)))]
    (if (error/anomaly? result) [] result)))

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
  one arrived.

  Three of the five bounds are here: the request timeout, redirects
  refused, and the response taken as a stream this reads a bounded
  prefix of. The address re-check is the caller's, immediately above;
  the per-endpoint bound is the claim's."
  [endpoint headers ^bytes body]
  (let [res (http/request {:method :post
                           :url (:address endpoint)
                           :headers (assoc headers
                                           "Content-Type"
                                           "application/json")
                           :body body
                           :as :stream
                           :timeout domain/request-timeout-ms
                           :follow-redirects false})]
    (if (error/anomaly? res)
      {:error (or (:message (error/payload res)) "webhook request failed")}
      (do (read-bounded (:body res))
          {:status (:status res)}))))

(defn- attempt-row
  [delivery outcome now duration-ms]
  (utility/assoc-some {:bank-id (:bank-id delivery)
                       :attempt-id (utility/generate-id "wha")
                       :delivery-id (:delivery-id delivery)
                       :attempted-at now
                       :duration-ms duration-ms}
                      :response-status (:status outcome)
                      :error (:error outcome)))

(defn- pause-endpoint
  "Pause the endpoint through the same transition every caller takes,
  so the guard runs against the record as it stands now and the write
  co-commits its changelog envelope. A guard that refuses — a tenant
  disabled the endpoint while the call was in flight — leaves the
  delivery's own outcome untouched."
  [config endpoint]
  (let [res (core/pause config (:bank-id endpoint) (:endpoint-id endpoint))]
    (when (error/anomaly? res)
      (log/info "Webhook endpoint not paused"
                {:endpoint-id (:endpoint-id endpoint) :anomaly res}))))

(defn address-refusal
  "Why the endpoint's address may not be called now, or nil. The host
  is resolved again here and the registration-time rule applied to what
  it answers, so an address whose DNS has moved into a range no tenant
  may be reached on is refused before the request is made."
  [address platform-hosts]
  (domain/check-address address (resolved address) platform-hosts))

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
        refused (check (:address endpoint) (:platform-hosts config))]
    (if refused
      {:error (or (:reason (error/payload refused)) "address refused")}
      (let [headers (signing/headers endpoint message-id body now)]
        (if (error/anomaly? headers)
          {:error "failed to sign delivery"}
          (post-notification endpoint headers body))))))

(defn deliver-claimed
  "Send one claimed delivery and record what came back. The call sits
  between two transactions and inside neither: the claim committed
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
    (let [started (utility/now)
          body (:body notification)
          outcome (outcome-of config
                              endpoint
                              body
                              (:delivery-id delivery)
                              started)
          now (utility/now)
          updated (domain/record-outcome delivery outcome now)]
      (let-nom>
        [_ (store/save-outcome config
                               updated
                               (attempt-row delivery
                                            outcome
                                            now
                                            (- now started)))]
        (when (and (not (domain/delivered? (:status outcome)))
                   (domain/should-pause? (:last-success-at endpoint)
                                         now
                                         (:attempts updated)))
          (pause-endpoint config endpoint))
        updated))))

(defn drain-once
  "Claim the deliveries that are due and send them. Each claim commits
  before its call is made, so a second replica draining at the same
  moment finds the rows claimed and sends nothing; the batch's calls
  run alongside each other and the pass ends when they have all
  recorded an outcome."
  [config]
  (let [claimed (store/claim-due-deliveries
                 config
                 {:now (utility/now)
                  :claimed-by (:runner-id config)
                  :lease-ms domain/claim-lease-ms
                  :limit (or (:batch-size config) default-batch-size)
                  :per-endpoint-limit domain/max-in-flight-per-endpoint})]
    (if (error/anomaly? claimed)
      (log/error "Failed to claim due webhook deliveries" {:anomaly claimed})
      (run! deref (mapv #(future (deliver-claimed config %)) claimed)))))

(defn start-runner
  "Start the daemon poll loop that drains due deliveries. Returns
  `{:stop fn}`."
  [config]
  (let [running (atom true)
        poll-ms (or (:poll-ms config) default-poll-ms)
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
