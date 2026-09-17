(ns com.repldriven.queenswood.clearbank-relay.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility :refer [assoc-some]]))

(def ^:private outbox-store-name "clearbank-outbox")

(def ^:private intents-store-name "clearbank-outbound-intents")

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-event
  "Persist an outbox event and append it to the store's changelog in a
  single transaction — the transactional-outbox write. A duplicate
  `dedup-key` fails the unique index."
  [txn event]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn outbox-store-name)
           ;; Captured here rather than by the caller because this runs on
           ;; the thread that holds the span, and every writer goes through
           ;; it. Absent when nothing is being traced — an optional proto
           ;; scalar wants the key gone, not nil.
           event (assoc-some event :traceparent (telemetry/inject-traceparent))]
       (let-nom>
         [_ (fdb/save-record store (schema/ClearbankOutboxEvent->java event))
          _ (fdb/write-changelog txn
                                 outbox-store-name
                                 (:outbox-id event)
                                 (schema/ClearbankOutboxEvent->pb event))]
         event)))
   :clearbank-outbox/save
   "Failed to save clearbank outbox event"))

(defn save-intent
  "Persist a pending outbound intent — the consume-side outbox write. A
  duplicate `dedup-key` (a redelivered submit-payment command) fails the
  unique index."
  [txn intent]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn intents-store-name)
                      (schema/ClearbankOutboundIntent->java intent)))
   :clearbank-outbound/save
   "Failed to save clearbank outbound intent"))

(defn pending-intents
  "Read every intent still `pending`, via the status index."
  [txn]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->ClearbankOutboundIntent
           (fdb/query-records (fdb/open txn intents-store-name)
                              "ClearbankOutboundIntent"
                              "status"
                              "pending"
                              {:index "ClearbankOutboundIntent_by_status"})))
   :clearbank-outbound/pending
   "Failed to read pending outbound intents"))

(defn- update-intent
  [txn intent-id f]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn intents-store-name)]
       (when-let [existing (some-> (fdb/load-record store intent-id)
                                   schema/pb->ClearbankOutboundIntent)]
         (fdb/save-record store
                          (schema/ClearbankOutboundIntent->java (f
                                                                 existing))))))
   :clearbank-outbound/update
   "Failed to update outbound intent"))

(defn mark-sent
  [txn intent-id]
  (update-intent txn
                 intent-id
                 (fn [i] (assoc i :status "sent" :sent-at (utility/now)))))

(defn mark-attempt
  [txn intent-id attempts next-attempt-at]
  (update-intent
   txn
   intent-id
   (fn [i]
     (assoc i :attempts attempts :next-attempt-at next-attempt-at))))

(defn fail-intent
  "Save a still-`pending` intent `failed` and write `event` to the outbox
  and its changelog in one transaction. An intent that is missing or no
  longer `pending` is returned unchanged with nothing written."
  [txn intent-id attempts event]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn intents-store-name)
           existing (some-> (fdb/load-record store intent-id)
                            schema/pb->ClearbankOutboundIntent)]
       (if (not= "pending" (:status existing))
         existing
         (let [failed (assoc existing :status "failed" :attempts attempts)]
           (let-nom>
             [_ (fdb/save-record store
                                 (schema/ClearbankOutboundIntent->java failed))
              _ (save-event txn event)]
             failed)))))
   :clearbank-outbound/fail
   "Failed to fail outbound intent"))
