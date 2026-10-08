(ns com.repldriven.queenswood.clearbank-relay.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def ^:private first-account-number
  "Where issued account numbers start, above the account numbers the
  simulator's test values and scenarios name for creditors outside the
  bank."
  20000000)

(def spec
  {:adapter :clearbank
   :outbox "clearbank-outbox"
   :intents "clearbank-outbound-intents"
   :intent-type "ClearbankOutboundIntent"
   :event-type "ClearbankOutboxEvent"
   :event->java schema/ClearbankOutboxEvent->java
   :event->pb schema/ClearbankOutboxEvent->pb
   :intent->java schema/ClearbankOutboundIntent->java
   :pb->intent schema/pb->ClearbankOutboundIntent})

(def transact intent-poller/transact)

(def uniqueness-violation? intent-poller/uniqueness-violation?)

(defn save-event [txn event] (intent-poller/save-event txn spec event))

(defn save-intent [txn intent] (intent-poller/save-intent txn spec intent))

(defn intents-with-status
  [config status]
  (intent-poller/intents-with-status (assoc config :store spec) status))

(defn finish
  [config intent-id status outcome attempt-count event]
  (intent-poller/finish config
                        spec
                        intent-id
                        status
                        outcome
                        attempt-count
                        event))

(defn mark-sent
  [config intent-id]
  (finish config
          intent-id
          :outbound-intent-status-pending
          :outbound-intent-status-sent
          nil
          nil))

(defn allocate-account-number
  [txn]
  (fdb/transact txn
                (fn [txn]
                  (format "%08d"
                          (+ first-account-number
                             (fdb/allocate-counter txn
                                                   (:intents spec)
                                                   "clearbank"
                                                   "counters"
                                                   "account-numbers"))))
                :clearbank-outbound/allocate-account-number
                "Failed to allocate an account number"))
