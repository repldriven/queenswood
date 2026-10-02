(ns com.repldriven.queenswood.modulr-relay.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility :refer [assoc-some]]

    [clojure.edn :as edn]))

(def ^:private outbox-store-name "modulr-outbox")

(def ^:private intents-store-name "modulr-outbound-intents")

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn- load-intent
  [store intent-id]
  (some-> (fdb/load-record store intent-id)
          schema/pb->ModulrOutboundIntent))

(defn- write-event
  [txn event]
  (let [store (fdb/open txn outbox-store-name)
        ;; Captured here, on the thread that holds the span, because every
        ;; writer goes through it. Absent when nothing is traced — an
        ;; optional proto scalar wants the key gone, not nil.
        event (assoc-some event :traceparent (telemetry/inject-traceparent))]
    (let-nom>
      [_ (fdb/save-record store (schema/ModulrOutboxEvent->java event))
       _ (fdb/write-changelog txn
                              outbox-store-name
                              (:outbox-id event)
                              (schema/ModulrOutboxEvent->pb event))]
      event)))

(defn- recorded?
  [txn dedup-key]
  (some? (fdb/query-record (fdb/open txn outbox-store-name)
                           "ModulrOutboxEvent"
                           "dedup_key"
                           dedup-key
                           {:index "ModulrOutboxEvent_by_dedup_key"})))

(defn find-intent
  [txn dedup-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record (fdb/open txn intents-store-name)
                               "ModulrOutboundIntent"
                               "dedup_key"
                               dedup-key
                               {:index "ModulrOutboundIntent_by_dedup_key"})
             schema/pb->ModulrOutboundIntent))
   :modulr-outbound/find
   "Failed to find an outbound intent"))

(defn- settle
  [txn dedup-key]
  (let-nom> [intent (find-intent txn dedup-key)]
    (when (= "sent" (:status intent))
      (fdb/save-record (fdb/open txn intents-store-name)
                       (schema/ModulrOutboundIntent->java
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
                 :modulr-outbox/save
                 "Failed to save modulr outbox event")))

(defn save-intent
  "Persist a pending intent. A duplicate `dedup-key` — a redelivered
  command — fails the unique index."
  [txn intent]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn intents-store-name)
                      (schema/ModulrOutboundIntent->java
                       (assoc-some intent
                                   :traceparent
                                   (telemetry/inject-traceparent)))))
   :modulr-outbound/save
   "Failed to save modulr outbound intent"))

(defn intents-with-status
  [txn status]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->ModulrOutboundIntent
           (fdb/query-records (fdb/open txn intents-store-name)
                              "ModulrOutboundIntent"
                              "status"
                              status
                              {:index "ModulrOutboundIntent_by_status"})))
   :modulr-outbound/by-status
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
                                 (schema/ModulrOutboundIntent->java updated))
              _ (when (and event (not (recorded? txn (:dedup-key event))))
                  (write-event txn event))]
             updated)))))
   :modulr-outbound/update
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

(defn- open-dedup-key
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
  (let-nom> [intent (find-intent txn (open-dedup-key account-id))]
    (when (= "settled" (:status intent))
      (:provider-account-id (intent-context intent)))))

(defn finish-holding
  "Move a `status` intent to `outcome` with `event`, recording in the
  same transaction that `account-id`'s money is now held in
  `provider-account-id`."
  [txn intent-id status outcome attempts event account-id provider-account-id]
  (fdb/transact
   txn
   (fn [txn]
     (let-nom> [finished (finish txn intent-id status outcome attempts event)
                opening (find-intent txn (open-dedup-key account-id))
                _ (when opening
                    (fdb/save-record
                     (fdb/open txn intents-store-name)
                     (schema/ModulrOutboundIntent->java
                      (assoc opening
                             :context
                             (pr-str (assoc (intent-context opening)
                                            :provider-account-id
                                            provider-account-id))))))]
       finished))
   :modulr-outbound/finish-holding
   "Failed to record the provider account an account is held in"))
