(ns com.repldriven.queenswood.zyphe-relay.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :refer [assoc-some]]))

(def ^:private outbox-store-name "zyphe-outbox")

(def ^:private intents-store-name "zyphe-outbound-intents")

(def uniqueness-violation? fdb/uniqueness-violation?)

(def transact fdb/transact)

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
                      (schema/ZypheOutboundIntent->java
                       (assoc-some intent
                                   :traceparent
                                   (telemetry/inject-traceparent)))))
   :zyphe-outbound/save
   "Failed to save zyphe outbound intent"))

(defn intents-with-status
  [txn status]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->ZypheOutboundIntent
           (fdb/query-records (fdb/open txn intents-store-name)
                              "ZypheOutboundIntent"
                              "status"
                              status
                              {:index "ZypheOutboundIntent_by_status"})))
   :zyphe-outbound/by-status
   "Failed to read outbound intents"))

(defn update-intent
  "Apply `f` to the intent while it is still `status`, and write `event`,
  when one is given, in the same transaction. An intent that is missing
  or has moved on is returned unchanged with nothing written."
  [txn intent-id status f event]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn intents-store-name)
           existing (some-> (fdb/load-record store intent-id)
                            schema/pb->ZypheOutboundIntent)]
       (if (not= status (:status existing))
         existing
         (let [updated (f existing)]
           (let-nom>
             [_ (fdb/save-record store
                                 (schema/ZypheOutboundIntent->java updated))
              _ (when event (save-event txn event))]
             updated)))))
   :zyphe-outbound/update
   "Failed to update outbound intent"))

(defn mark-attempt
  [txn intent-id attempts next-attempt-at]
  (update-intent
   txn
   intent-id
   "pending"
   (fn [i]
     (assoc i :attempts attempts :next-attempt-at next-attempt-at))
   nil))

(defn finish
  "Move a `status` intent to `outcome` with `event`."
  [txn intent-id status outcome attempts event]
  (update-intent txn
                 intent-id
                 status
                 (fn [i]
                   (assoc-some (assoc i :status outcome) :attempts attempts))
                 event))
