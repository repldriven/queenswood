(ns com.repldriven.queenswood.clearbank-relay.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility :refer [assoc-some]]))

(def ^:private outbox-store-name "clearbank-outbox")

(def ^:private intents-store-name "clearbank-outbound-intents")

(def ^:private first-account-number
  "Where issued account numbers start, above the account numbers the
  simulator's test values and scenarios name for creditors outside the
  bank."
  20000000)

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
                      (schema/ClearbankOutboundIntent->java
                       (assoc-some intent
                                   :traceparent
                                   (telemetry/inject-traceparent)))))
   :clearbank-outbound/save
   "Failed to save clearbank outbound intent"))

(defn intents-with-status
  [txn status]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->ClearbankOutboundIntent
           (fdb/query-records (fdb/open txn intents-store-name)
                              "ClearbankOutboundIntent"
                              "status"
                              status
                              {:index "ClearbankOutboundIntent_by_status"})))
   :clearbank-outbound/by-status
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
                            schema/pb->ClearbankOutboundIntent)]
       (if (not= status (:status existing))
         existing
         (let [updated (f existing)]
           (let-nom>
             [_ (fdb/save-record store
                                 (schema/ClearbankOutboundIntent->java updated))
              _ (when event (save-event txn event))]
             updated)))))
   :clearbank-outbound/update
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

(defn mark-sent
  [txn intent-id]
  (update-intent txn
                 intent-id
                 "pending"
                 (fn [i] (assoc i :status "sent" :sent-at (utility/now)))
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

(defn allocate-account-number
  [txn]
  (fdb/transact txn
                (fn [txn]
                  (format "%08d"
                          (+ first-account-number
                             (fdb/allocate-counter txn
                                                   intents-store-name
                                                   "clearbank"
                                                   "counters"
                                                   "account-numbers"))))
                :clearbank-outbound/allocate-account-number
                "Failed to allocate an account number"))
