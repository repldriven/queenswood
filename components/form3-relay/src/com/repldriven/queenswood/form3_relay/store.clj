(ns com.repldriven.queenswood.form3-relay.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility :refer [assoc-some]]))

(def ^:private outbox-store-name "form3-outbox")

(def ^:private intents-store-name "form3-outbound-intents")

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn- load-intent
  [store intent-id]
  (some-> (fdb/load-record store intent-id)
          schema/pb->Form3OutboundIntent))

(defn- write-event
  [txn event]
  (let [store (fdb/open txn outbox-store-name)
        ;; Captured here, on the thread that holds the span, because every
        ;; writer goes through it. Absent when nothing is traced — an
        ;; optional proto scalar wants the key gone, not nil.
        event (assoc-some event :traceparent (telemetry/inject-traceparent))]
    (let-nom>
      [_ (fdb/save-record store (schema/Form3OutboxEvent->java event))
       _ (fdb/write-changelog txn
                              outbox-store-name
                              (:outbox-id event)
                              (schema/Form3OutboxEvent->pb event))]
      event)))

(defn- recorded?
  [txn dedup-key]
  (some? (fdb/query-record (fdb/open txn outbox-store-name)
                           "Form3OutboxEvent"
                           "dedup_key"
                           dedup-key
                           {:index "Form3OutboxEvent_by_dedup_key"})))

(defn find-intent
  [txn dedup-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record (fdb/open txn intents-store-name)
                               "Form3OutboundIntent"
                               "dedup_key"
                               dedup-key
                               {:index "Form3OutboundIntent_by_dedup_key"})
             schema/pb->Form3OutboundIntent))
   :form3-outbound/find
   "Failed to find an outbound intent"))

(defn- settle
  [txn dedup-key]
  (let-nom> [intent (find-intent txn dedup-key)]
    (when (= "sent" (:status intent))
      (fdb/save-record (fdb/open txn intents-store-name)
                       (schema/Form3OutboundIntent->java
                        (assoc intent :status "settled"))))))

(defn save-event
  "Persist an outbox event and append it to the store's changelog in one
  transaction. A duplicate `dedup-key` fails the unique index. With
  `settles`, the sent intent carrying that dedup key is settled in the
  same transaction, so its reconciliation does not run."
  ([txn event]
   (save-event txn event nil))
  ([txn event settles]
   (fdb/transact txn
                 (fn [txn]
                   (let-nom> [saved (write-event txn event)
                              _ (when settles (settle txn settles))]
                     saved))
                 :form3-outbox/save
                 "Failed to save form3 outbox event")))

(defn save-intent
  "Persist a pending intent. A duplicate `dedup-key` — a redelivered
  command — fails the unique index."
  [txn intent]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn intents-store-name)
                      (schema/Form3OutboundIntent->java
                       (assoc-some intent
                                   :traceparent
                                   (telemetry/inject-traceparent)))))
   :form3-outbound/save
   "Failed to save form3 outbound intent"))

(defn intents-with-status
  [txn status]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->Form3OutboundIntent
           (fdb/query-records (fdb/open txn intents-store-name)
                              "Form3OutboundIntent"
                              "status"
                              status
                              {:index "Form3OutboundIntent_by_status"})))
   :form3-outbound/by-status
   "Failed to read outbound intents"))

(defn update-intent
  "Apply `f` to the intent while it is still `status`, and write
  `event`, when one is given and no webhook has recorded its dedup key,
  in the same transaction. An intent that is missing or has moved on is
  returned unchanged with nothing written."
  [txn intent-id status f event]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn intents-store-name)
           existing (load-intent store intent-id)]
       (if (not= status (:status existing))
         existing
         (let [updated (f existing)]
           (let-nom>
             [_ (fdb/save-record store
                                 (schema/Form3OutboundIntent->java updated))
              _ (when (and event (not (recorded? txn (:dedup-key event))))
                  (write-event txn event))]
             updated)))))
   :form3-outbound/update
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
  [txn intent-id provider-payment-id reconcile-at]
  (update-intent txn
                 intent-id
                 "pending"
                 (fn [i]
                   (assoc-some (assoc i
                                      :status "sent"
                                      :sent-at (utility/now)
                                      :next-attempt-at reconcile-at)
                               :provider-payment-id
                               provider-payment-id))
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

(def ^:private first-account-number 30000001)

(defn allocate-account-number
  [txn]
  (fdb/transact txn
                (fn [txn]
                  (format "%08d"
                          (+ first-account-number
                             (fdb/allocate-counter txn
                                                   intents-store-name
                                                   "form3"
                                                   "counters"
                                                   "account-numbers"))))
                :form3-outbound/allocate-account-number
                "Failed to allocate an account number"))
