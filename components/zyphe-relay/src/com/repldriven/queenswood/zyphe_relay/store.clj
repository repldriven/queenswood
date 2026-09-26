(ns com.repldriven.queenswood.zyphe-relay.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility :refer [assoc-some]]))

(def ^:private outbox-store-name "zyphe-outbox")

(def ^:private intents-store-name "zyphe-outbound-intents")

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-event
  "Persist an outbox event and append it to the store's changelog in a
  single transaction. A duplicate `dedup-key` fails the unique index."
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
         [_ (fdb/save-record store (schema/ZypheOutboxEvent->java event))
          _ (fdb/write-changelog txn
                                 outbox-store-name
                                 (:outbox-id event)
                                 (schema/ZypheOutboxEvent->pb event))]
         event)))
   :zyphe-outbox/save
   "Failed to save zyphe outbox event"))

(defn save-intent
  "Persist a pending outbound intent — the consume-side outbox write. A
  duplicate `dedup-key` (a redelivered submit-idv-check command) fails
  the unique index."
  [txn intent]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn intents-store-name)
                      (schema/ZypheOutboundIntent->java intent)))
   :zyphe-outbound/save
   "Failed to save zyphe outbound intent"))

(defn pending-intents
  "Read every intent still `pending`, via the status index."
  [txn]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->ZypheOutboundIntent
           (fdb/query-records (fdb/open txn intents-store-name)
                              "ZypheOutboundIntent"
                              "status"
                              "pending"
                              {:index "ZypheOutboundIntent_by_status"})))
   :zyphe-outbound/pending
   "Failed to read pending outbound intents"))

(defn- update-intent
  [txn intent-id f]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn intents-store-name)]
       (when-let [existing (some-> (fdb/load-record store intent-id)
                                   schema/pb->ZypheOutboundIntent)]
         (fdb/save-record store
                          (schema/ZypheOutboundIntent->java (f existing))))))
   :zyphe-outbound/update
   "Failed to update outbound intent"))

(defn mark-sent
  [txn intent-id]
  (update-intent txn
                 intent-id
                 (fn [i] (assoc i :status "sent" :sent-at (utility/now)))))

(defn mark-attempt
  [txn intent-id attempts]
  (update-intent txn intent-id (fn [i] (assoc i :attempts attempts))))

(defn mark-failed
  [txn intent-id attempts]
  (update-intent txn
                 intent-id
                 (fn [i] (assoc i :status "failed" :attempts attempts))))
