(ns com.repldriven.queenswood.email.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private deliveries-store-name "email-deliveries")

(def ^:private pending :email-delivery-status-pending)
(def ^:private in-flight :email-delivery-status-in-flight)

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-delivery
  [txn delivery]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn deliveries-store-name)
                      (schema/EmailDelivery->java
                       (cond-> delivery
                               (nil? (:traceparent delivery))
                               (utility/assoc-some
                                :traceparent
                                (telemetry/inject-traceparent))))))
   :email-delivery/save
   "Failed to save email delivery"))

(defn find-delivery
  [txn bank-id delivery-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn deliveries-store-name)
                              bank-id
                              delivery-id)
             schema/pb->EmailDelivery))
   :email-delivery/find
   "Failed to load email delivery"))

(defn find-delivery-by-idempotency-key
  [txn idempotency-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record (fdb/open txn deliveries-store-name)
                               "EmailDelivery"
                               "idempotency_key"
                               idempotency-key
                               {:index "EmailDelivery_by_idempotency_key"})
             schema/pb->EmailDelivery))
   :email-delivery/find-by-idempotency-key
   "Failed to find email delivery by idempotency key"))

(defn- later?
  [a b]
  (pos? (compare [(:created-at a) (:delivery-id a)]
                 [(:created-at b) (:delivery-id b)])))

(defn newer-delivery?
  "Whether a delivery of the same kind about the same subject was written
  after `delivery`."
  [txn delivery]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn deliveries-store-name)
           {:keys [bank-id kind subject-id]} delivery]
       (->> (fdb/query-records-compound
             store
             "EmailDelivery"
             [["bank_id" bank-id]
              ["kind"
               (fdb/enum-value store
                               "EmailDelivery"
                               "kind"
                               (schema/email-kind->int kind))]
              ["subject_id" subject-id]]
             {:index "EmailDelivery_by_subject"})
            (map schema/pb->EmailDelivery)
            (some (fn [other] (later? other delivery)))
            boolean)))
   :email-delivery/find-by-subject
   "Failed to find email deliveries by subject"))

(def ^:private scan-factor
  "How many rows a claim reads for each it may take."
  10)

(defn- due-deliveries
  "Up to `limit` of the deliveries in `status` whose next attempt is due
  by `now`, soonest first."
  [store status limit now]
  (fdb/scan-index-records store
                          "EmailDelivery_by_status_due"
                          [(schema/email-delivery-status->int status)]
                          {:limit limit :through [now]}))

(defn claim-due-deliveries
  "Claim the deliveries that are due in the transaction that read them:
  each moves to in flight with its next attempt `lease-ms` away, when
  the claim lapses if the runner holding it stops before the outcome. A
  second runner reading the same rows loses the transaction and claims
  nothing. In-flight rows whose claim has lapsed are scanned before
  pending ones, so a pending backlog cannot starve their recovery."
  [txn {:keys [now lease-ms limit]}]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn deliveries-store-name)
           due (into []
                     (comp (map schema/pb->EmailDelivery)
                           (take limit))
                     (concat (due-deliveries store
                                             in-flight
                                             (* scan-factor limit)
                                             now)
                             (due-deliveries store
                                             pending
                                             (* scan-factor limit)
                                             now)))]
       (reduce (fn [claimed delivery]
                 (let [row (assoc delivery
                                  :status in-flight
                                  :next-attempt-at (+ now lease-ms)
                                  :updated-at now)
                       res (fdb/save-record store
                                            (schema/EmailDelivery->java row))]
                   (if (error/anomaly? res) (reduced res) (conj claimed row))))
               []
               due)))
   :email-delivery/claim
   "Failed to claim due email deliveries"))
