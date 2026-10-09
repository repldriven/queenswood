(ns com.repldriven.queenswood.webhook.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private endpoints-store-name "webhook-endpoints")
(def ^:private notifications-store-name "webhook-notifications")
(def ^:private deliveries-store-name "webhook-deliveries")
(def ^:private attempts-store-name "webhook-delivery-attempts")

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

;; ---------------------------------------------------------------------------
;; Endpoints

(defn save-endpoint
  [txn endpoint]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn endpoints-store-name)
                      (schema/WebhookEndpoint->java endpoint)))
   :webhook-endpoint/save
   "Failed to save webhook endpoint"))

(defn find-endpoint
  [txn bank-id endpoint-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn endpoints-store-name)
                              bank-id
                              endpoint-id)
             schema/pb->WebhookEndpoint))
   :webhook-endpoint/find
   "Failed to load webhook endpoint"))

(defn find-endpoint-by-idempotency-key
  [txn bank-id idempotency-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record-compound
              (fdb/open txn endpoints-store-name)
              "WebhookEndpoint"
              [["bank_id" bank-id]
               ["idempotency_key" idempotency-key]]
              {:index "WebhookEndpoint_by_idempotency_key"})
             schema/pb->WebhookEndpoint))
   :webhook-endpoint/find-by-idempotency-key
   "Failed to find webhook endpoint by idempotency key"))

(defn get-endpoints
  ([txn bank-id]
   (get-endpoints txn bank-id nil))
  ([txn bank-id opts]
   (let [{:keys [after before limit order]
          :or {limit 100 order :asc}}
         opts]
     (let-nom>
       [result (fdb/transact
                txn
                (fn [txn]
                  (fdb/scan-records
                   (fdb/open txn endpoints-store-name)
                   {:prefix [bank-id]
                    :after after
                    :before before
                    :limit limit
                    :order order}))
                :webhook-endpoint/list
                "Failed to list webhook endpoints")
        {:keys [records before after]} result]
       {:endpoints (mapv schema/pb->WebhookEndpoint records)
        :before before
        :after after}))))

(defn count-endpoints
  [txn bank-id]
  (fdb/transact
   txn
   (fn [txn]
     ;; Group key is [bank_id, endpoint_id], so counting groups under the
     ;; [bank_id] prefix yields the bank's endpoints.
     (fdb/count-groups (fdb/open txn endpoints-store-name)
                       "WebhookEndpoint_count_by_bank"
                       [bank-id]))
   :webhook-endpoint/count
   {:message "Failed to count webhook endpoints"
    :bank-id bank-id}))

;; ---------------------------------------------------------------------------
;; Notifications

(defn save-notification
  [txn notification]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn notifications-store-name)
                      (schema/WebhookNotification->java notification)))
   :webhook-notification/save
   "Failed to save webhook notification"))

(defn find-notification
  [txn bank-id notification-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn notifications-store-name)
                              bank-id
                              notification-id)
             schema/pb->WebhookNotification))
   :webhook-notification/find
   "Failed to load webhook notification"))

(defn find-notification-by-idempotency-key
  [txn bank-id idempotency-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record-compound
              (fdb/open txn notifications-store-name)
              "WebhookNotification"
              [["bank_id" bank-id] ["idempotency_key" idempotency-key]]
              {:index "WebhookNotification_by_idempotency_key"})
             schema/pb->WebhookNotification))
   :webhook-notification/find-by-idempotency-key
   "Failed to find webhook notification by idempotency key"))

(defn find-notifications-by-bank
  [txn bank-id]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->WebhookNotification
           (fdb/query-records (fdb/open txn notifications-store-name)
                              "WebhookNotification"
                              "bank_id"
                              bank-id
                              {:index "WebhookNotification_by_bank_created"})))
   :webhook-notification/find-by-bank
   "Failed to find webhook notifications by bank"))

;; ---------------------------------------------------------------------------
;; Deliveries

(defn save-delivery
  [txn delivery]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn deliveries-store-name)
                      (schema/WebhookDelivery->java
                       (cond-> delivery
                               (nil? (:traceparent delivery))
                               (utility/assoc-some
                                :traceparent
                                (telemetry/inject-traceparent))))))
   :webhook-delivery/save
   "Failed to save webhook delivery"))

(defn find-delivery
  [txn bank-id delivery-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn deliveries-store-name)
                              bank-id
                              delivery-id)
             schema/pb->WebhookDelivery))
   :webhook-delivery/find
   "Failed to load webhook delivery"))

(defn find-deliveries-by-status
  [txn status]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn deliveries-store-name)]
       (mapv schema/pb->WebhookDelivery
             (fdb/query-records
              store
              "WebhookDelivery"
              "status"
              (fdb/enum-value store
                              "WebhookDelivery"
                              "status"
                              (schema/webhook-delivery-status->int status))
              {:index "WebhookDelivery_by_status_due"}))))
   :webhook-delivery/find-by-status
   "Failed to find webhook deliveries by status"))

(defn find-deliveries-by-endpoint
  [txn endpoint-id]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->WebhookDelivery
           (fdb/query-records (fdb/open txn deliveries-store-name)
                              "WebhookDelivery"
                              "endpoint_id"
                              endpoint-id
                              {:index "WebhookDelivery_by_endpoint_created"})))
   :webhook-delivery/find-by-endpoint
   "Failed to find webhook deliveries by endpoint"))

;; ---------------------------------------------------------------------------
;; Delivery attempts

(defn save-attempt
  [txn attempt]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn attempts-store-name)
                      (schema/WebhookDeliveryAttempt->java attempt)))
   :webhook-delivery-attempt/save
   "Failed to save webhook delivery attempt"))

(defn find-attempt
  [txn bank-id attempt-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn attempts-store-name)
                              bank-id
                              attempt-id)
             schema/pb->WebhookDeliveryAttempt))
   :webhook-delivery-attempt/find
   "Failed to load webhook delivery attempt"))

(defn find-attempts-by-delivery
  [txn delivery-id]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->WebhookDeliveryAttempt
           (fdb/query-records (fdb/open txn attempts-store-name)
                              "WebhookDeliveryAttempt"
                              "delivery_id"
                              delivery-id
                              {:index "WebhookDeliveryAttempt_by_delivery"})))
   :webhook-delivery-attempt/find-by-delivery
   "Failed to find webhook delivery attempts by delivery"))

(def ^:private delivery-pending :webhook-delivery-status-pending)
(def ^:private delivery-in-flight :webhook-delivery-status-in-flight)

(def ^:private scan-factor
  "How many rows a claim reads for each it may take, so the per-endpoint
  limit can pass over some and still fill the batch."
  10)

(defn- deliveries-with-status
  "Up to `limit` of the deliveries in `status`, due first, and with
  `due-by`, only those due by then."
  [store status limit due-by]
  (fdb/scan-index-records store
                          "WebhookDelivery_by_status_due"
                          [(schema/webhook-delivery-status->int status)]
                          (cond-> {:limit limit}

                                  due-by
                                  (assoc :through [due-by]))))

(defn- claimable?
  "Whether a row may be claimed now: a pending one whose next attempt
  has come, or an in-flight one whose lease has passed — the runner
  holding it died between the claim commit and the outcome commit, and
  nothing else would ever look at the row again."
  [{:keys [status next-attempt-at claim-lease-expires-at]} now]
  (if (= delivery-in-flight status)
    (<= (or claim-lease-expires-at 0) now)
    (<= (or next-attempt-at 0) now)))

(defn claim-due-deliveries
  "Claim the deliveries that are due, in the transaction that read
  them: each takes a lease and the claiming runner, and moves to
  in-flight if it was not already. A second replica reading the same
  row loses this transaction and claims nothing, so a due delivery is
  sent once.

  Rows whose claim lease has expired are scanned before the pending
  ones, so a pending backlog of more than `:limit` rows cannot starve
  the recovery.

  Returns the claimed deliveries as they were written.

  Args:
  - txn: FDB transaction or config map.
  - opts:
    - `:now` — epoch-ms; a pending delivery is due when its next
      attempt is at or before this, and one that has never been
      attempted has none. An in-flight delivery is due again when its
      claim lease is at or before this.
    - `:claimed-by` — the runner's id, stamped on the row.
    - `:lease-ms` — how long the claim holds.
    - `:limit` — how many to claim in this pass.
    - `:per-endpoint-limit` — how many of one endpoint's deliveries the
      batch may carry, so one tenant cannot fill it.
    - `:endpoint-allowance` — optional, `(fn [txn bank-id endpoint-id])`,
      asked in
      this transaction the first time an endpoint's delivery is reached,
      answering how many of its deliveries the batch may carry at most."
  [txn
   {:keys [now claimed-by lease-ms limit per-endpoint-limit
           endpoint-allowance]}]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn deliveries-store-name)
           taken (volatile! {})
           allowances (volatile! {})
           allowance (fn [{:keys [bank-id endpoint-id]}]
                       (when-not (contains? @allowances endpoint-id)
                         (vswap! allowances
                                 assoc
                                 endpoint-id
                                 (cond-> per-endpoint-limit
                                         endpoint-allowance
                                         (min (endpoint-allowance
                                               txn
                                               bank-id
                                               endpoint-id)))))
                       (get @allowances endpoint-id))
           within-endpoint-limit?
           (fn [{:keys [endpoint-id] :as delivery}]
             (when (< (get @taken endpoint-id 0) (allowance delivery))
               (vswap! taken update endpoint-id (fnil inc 0))
               true))
           due (into []
                     (comp (map schema/pb->WebhookDelivery)
                           (filter #(claimable? % now))
                           (filter within-endpoint-limit?)
                           (take limit))
                     (concat (deliveries-with-status store
                                                     delivery-in-flight
                                                     (* scan-factor limit)
                                                     nil)
                             (deliveries-with-status store
                                                     delivery-pending
                                                     (* scan-factor limit)
                                                     now)))]
       (reduce (fn [claimed delivery]
                 (let [row (assoc delivery
                                  :status delivery-in-flight
                                  :claim-lease-expires-at (+ now lease-ms)
                                  :claimed-by claimed-by
                                  :updated-at now)
                       res (fdb/save-record
                            store
                            (schema/WebhookDelivery->java row))]
                   (if (error/anomaly? res) (reduced res) (conj claimed row))))
               []
               due)))
   :webhook-delivery/claim
   "Failed to claim due webhook deliveries"))

(defn save-outcome
  "Write one attempt's outcome: the delivery as the outcome left it and
  the attempt row for the call, in one transaction of their own — the
  HTTP call has already happened outside every transaction."
  [txn delivery attempt]
  (fdb/transact
   txn
   (fn [txn]
     (let-nom>
       [_ (fdb/save-record (fdb/open txn deliveries-store-name)
                           (schema/WebhookDelivery->java delivery))
        _ (fdb/save-record (fdb/open txn attempts-store-name)
                           (schema/WebhookDeliveryAttempt->java attempt))]
       nil))
   :webhook-delivery/save-outcome
   "Failed to record webhook delivery outcome"))
