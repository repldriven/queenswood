(ns com.repldriven.queenswood.intent-poller.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :refer [assoc-some]]))

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
  [txn spec status]
  (let [{:keys [intents intent-type pb->intent]} spec]
    (fdb/transact
     txn
     (fn [txn]
       (mapv pb->intent
             (fdb/query-records (fdb/open txn intents)
                                intent-type
                                "status"
                                status
                                {:index (str intent-type "_by_status")})))
     (category spec "outbound" "by-status")
     "Failed to read outbound intents")))

(defn update-intent
  [txn spec intent-id status f event]
  (let [{:keys [intents intent->java pb->intent]} spec]
    (fdb/transact
     txn
     (fn [txn]
       (let [store (fdb/open txn intents)
             existing (some-> (fdb/load-record store intent-id)
                              pb->intent)]
         (if (not= status (:status existing))
           existing
           (let [updated (f existing)]
             (let-nom>
               [_ (fdb/save-record store (intent->java updated))
                _ (when event (save-event txn spec event))]
               updated)))))
     (category spec "outbound" "update")
     "Failed to update outbound intent")))

(defn mark-attempt
  [txn spec intent-id attempts next-attempt-at]
  (update-intent
   txn
   spec
   intent-id
   "pending"
   (fn [i]
     (assoc i :attempts attempts :next-attempt-at next-attempt-at))
   nil))

(defn finish
  [txn spec intent-id status outcome attempts event]
  (update-intent txn
                 spec
                 intent-id
                 status
                 (fn [i]
                   (assoc-some (assoc i :status outcome) :attempts attempts))
                 event))
