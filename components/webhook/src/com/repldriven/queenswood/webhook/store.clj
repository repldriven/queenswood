(ns com.repldriven.queenswood.webhook.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

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

(defn find-notification-by-changelog-event-id
  [txn changelog-event-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record
              (fdb/open txn notifications-store-name)
              "WebhookNotification"
              "changelog_event_id"
              changelog-event-id
              {:index "WebhookNotification_by_changelog_event_id"})
             schema/pb->WebhookNotification))
   :webhook-notification/find-by-changelog-event-id
   "Failed to find webhook notification by changelog event id"))

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
                      (schema/WebhookDelivery->java delivery)))
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
