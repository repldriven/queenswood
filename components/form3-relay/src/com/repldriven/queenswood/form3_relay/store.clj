(ns com.repldriven.queenswood.form3-relay.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(def spec
  {:adapter :form3
   :outbox "form3-outbox"
   :intents "form3-outbound-intents"
   :intent-type "Form3OutboundIntent"
   :event-type "Form3OutboxEvent"
   :event->java schema/Form3OutboxEvent->java
   :event->pb schema/Form3OutboxEvent->pb
   :intent->java schema/Form3OutboundIntent->java
   :pb->intent schema/pb->Form3OutboundIntent})

(def transact intent-poller/transact)

(def uniqueness-violation? intent-poller/uniqueness-violation?)

(defn find-intent
  [txn dedup-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record (fdb/open txn (:intents spec))
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
    (when (= :outbound-intent-status-sent (:status intent))
      (fdb/save-record
       (fdb/open txn (:intents spec))
       (schema/Form3OutboundIntent->java
        (assoc intent :status :outbound-intent-status-settled))))))

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
                   (let-nom> [saved (intent-poller/save-event txn spec event)
                              _ (when settles (settle txn settles))]
                     saved))
                 :form3-outbox/save
                 "Failed to save form3 outbox event")))

(defn save-intent [txn intent] (intent-poller/save-intent txn spec intent))

(defn advance
  [txn intent-id ctx]
  (intent-poller/advance txn spec intent-id ctx nil))

(def ^:private first-account-number 30000001)

(defn allocate-account-number
  [txn]
  (fdb/transact txn
                (fn [txn]
                  (format "%08d"
                          (+ first-account-number
                             (fdb/allocate-counter txn
                                                   (:intents spec)
                                                   "form3"
                                                   "counters"
                                                   "account-numbers"))))
                :form3-outbound/allocate-account-number
                "Failed to allocate an account number"))
