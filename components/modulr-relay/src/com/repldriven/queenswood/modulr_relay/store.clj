(ns com.repldriven.queenswood.modulr-relay.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]

    [clojure.edn :as edn]))

(def spec
  {:adapter :modulr
   :outbox "modulr-outbox"
   :intents "modulr-outbound-intents"
   :intent-type "ModulrOutboundIntent"
   :event-type "ModulrOutboxEvent"
   :event->java schema/ModulrOutboxEvent->java
   :event->pb schema/ModulrOutboxEvent->pb
   :intent->java schema/ModulrOutboundIntent->java
   :pb->intent schema/pb->ModulrOutboundIntent})

(def transact intent-poller/transact)

(def uniqueness-violation? intent-poller/uniqueness-violation?)

(defn find-intent
  [txn idempotency-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record (fdb/open txn (:intents spec))
                               "ModulrOutboundIntent"
                               "idempotency_key"
                               idempotency-key
                               {:index
                                "ModulrOutboundIntent_by_idempotency_key"})
             schema/pb->ModulrOutboundIntent))
   :modulr-outbound/find
   "Failed to find an outbound intent"))

(defn- settle
  [txn idempotency-key]
  (let-nom> [intent (find-intent txn idempotency-key)]
    (when (= :outbound-intent-status-sent (:status intent))
      (fdb/save-record
       (fdb/open txn (:intents spec))
       (schema/ModulrOutboundIntent->java
        (assoc intent :status :outbound-intent-status-settled))))))

(defn save-event
  "Persist an outbox event and append it to the store's changelog in one
  transaction. A duplicate `dedup-key` fails the unique index. With
  `settles`, the sent intent carrying that idempotency key is settled in the
  same transaction, so its reconciliation does not run."
  ([txn event]
   (save-event txn event nil))
  ([txn event settles]
   (fdb/transact txn
                 (fn [txn]
                   (let-nom> [saved (intent-poller/save-event txn spec event)
                              _ (when settles (settle txn settles))]
                     saved))
                 :modulr-outbox/save
                 "Failed to save modulr outbox event")))

(defn save-intent [txn intent] (intent-poller/save-intent txn spec intent))

(defn- open-idempotency-key
  [account-id]
  (str "open:" account-id))

(defn- intent-context
  [intent]
  (or (some-> (not-empty (:context intent))
              edn/read-string)
      {}))

(defn provider-account
  "The provider account holding `account-id`'s money, as its opening, or
  the reissue that last replaced it, recorded it; nil where the account
  was not opened here or is not open yet."
  [txn account-id]
  (let-nom> [intent (find-intent txn (open-idempotency-key account-id))]
    (when (= :outbound-intent-status-settled (:status intent))
      (:provider-account-id (intent-context intent)))))

(defn hold
  "Record on `account-id`'s opening that its money is now held in
  `provider-account-id`, in the transaction `txn` is."
  [txn account-id provider-account-id]
  (let-nom> [opening (find-intent txn (open-idempotency-key account-id))]
    (when opening
      (fdb/save-record (fdb/open txn (:intents spec))
                       (schema/ModulrOutboundIntent->java
                        (assoc opening
                               :context
                               (pr-str (assoc (intent-context opening)
                                              :provider-account-id
                                              provider-account-id))))))))

(defn advance
  [txn intent-id ctx changes]
  (intent-poller/advance txn spec intent-id ctx changes))
