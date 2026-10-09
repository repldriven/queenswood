(ns com.repldriven.queenswood.intent-poller.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.transit.interface :as transit]
    [com.repldriven.mono.utility.interface :as utility :refer [assoc-some]]))

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn- category
  [spec store op]
  (keyword (str (name (:adapter spec)) "-" store) op))

(defn save-event
  [txn spec event]
  (let [{:keys [outbox event->java event->pb]} spec]
    (fdb/transact
     txn
     (fn [txn]
       (let [store (fdb/open txn outbox)
             ;; Captured here rather than by the caller because this runs
             ;; on the thread that holds the span, and every writer goes
             ;; through it. Absent when nothing is being traced — an
             ;; optional proto scalar wants the key gone, not nil.
             event (assoc-some event
                               :traceparent
                               (telemetry/inject-traceparent))]
         (let-nom>
           [_ (fdb/save-record store (event->java event))
            _ (fdb/write-changelog txn
                                   outbox
                                   (:outbox-id event)
                                   (event->pb event))]
           event)))
     (category spec "outbox" "save")
     "Failed to save outbox event")))

(defn save-intent
  [txn spec intent]
  (let [{:keys [intents intent->java]} spec]
    (fdb/transact
     txn
     (fn [txn]
       (fdb/save-record (fdb/open txn intents)
                        (intent->java (assoc-some
                                       intent
                                       :traceparent
                                       (telemetry/inject-traceparent)))))
     (category spec "outbound" "save")
     "Failed to save outbound intent")))

(defn intents-with-status
  ([txn spec status]
   (intents-with-status txn spec status nil))
  ([txn spec status limit]
   (let [{:keys [intents intent-type pb->intent]} spec]
     (fdb/transact
      txn
      (fn [txn]
        (mapv pb->intent
              (if limit
                (fdb/scan-index-records (fdb/open txn intents)
                                        (str intent-type "_by_status")
                                        [(schema/outbound-intent-status->int
                                          status)]
                                        {:limit limit})
                (fdb/query-records (fdb/open txn intents)
                                   intent-type
                                   "status"
                                   (schema/outbound-intent-status->pb-enum
                                    status)
                                   {:index (str intent-type "_by_status")}))))
      (category spec "outbound" "by-status")
      "Failed to read outbound intents"))))

(defn- recorded?
  [txn spec dedup-key]
  (let [{:keys [outbox event-type]} spec]
    (some? (fdb/query-record (fdb/open txn outbox)
                             event-type
                             "dedup_key"
                             dedup-key
                             {:index (str event-type "_by_dedup_key")}))))

(defn- redacted
  [spec intent]
  (let [{:keys [redact]} spec]
    (if (and redact
             (#{:outbound-intent-status-settled :outbound-intent-status-failed}
              (:status intent)))
      (update intent :request redact)
      intent)))

(defn update-intent
  ([txn spec intent-id status f event]
   (update-intent txn spec intent-id status f event nil))
  ([txn spec intent-id status f event also]
   (let [{:keys [intents intent->java pb->intent]} spec]
     (fdb/transact
      txn
      (fn [txn]
        (let [store (fdb/open txn intents)
              existing (some-> (fdb/load-record store intent-id)
                               pb->intent)]
          (if (not= status (:status existing))
            existing
            (let [updated (redacted spec (f existing))]
              (let-nom>
                [_ (fdb/save-record store (intent->java updated))
                 _ (when (and event
                              (not (recorded? txn spec (:dedup-key event))))
                     (save-event txn spec event))
                 _ (when also (also txn))]
                updated)))))
      (category spec "outbound" "update")
      "Failed to update outbound intent"))))

(defn moved
  "`intent` moved to `outcome`, with `attempt-count` where given and
  `changes` merged in. A move to `sent` records when it was sent."
  [intent outcome attempt-count changes]
  (cond-> (merge (assoc-some (assoc intent :status outcome)
                             :attempt-count
                             attempt-count)
                 changes)
          (and (= :outbound-intent-status-sent outcome)
               (not= :outbound-intent-status-sent (:status intent)))
          (assoc :sent-at (utility/now))))

(defn advanced
  "`intent` kept pending with `ctx` for its next step, from a fresh
  attempt count, `changes` merged in."
  [intent ctx changes]
  (-> intent
      (assoc :context (transit/write-str ctx) :attempt-count 0)
      (dissoc :next-attempt-at)
      (merge changes)))

(defn advance
  [txn spec intent-id ctx changes]
  (update-intent txn
                 spec
                 intent-id
                 :outbound-intent-status-pending
                 (fn [i] (advanced i ctx changes))
                 nil))

(defn mark-attempt
  [txn spec intent-id attempt-count next-attempt-at]
  (update-intent
   txn
   spec
   intent-id
   :outbound-intent-status-pending
   (fn [i]
     (assoc i :attempt-count attempt-count :next-attempt-at next-attempt-at))
   nil))

(defn finish
  ([txn spec intent-id status outcome attempt-count event]
   (finish txn spec intent-id status outcome attempt-count event nil))
  ([txn spec intent-id status outcome attempt-count event changes]
   (update-intent txn
                  spec
                  intent-id
                  status
                  (fn [i] (moved i outcome attempt-count changes))
                  event)))
