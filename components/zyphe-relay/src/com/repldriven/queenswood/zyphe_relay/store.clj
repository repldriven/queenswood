(ns com.repldriven.queenswood.zyphe-relay.store
  (:require
    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def spec
  {:adapter :zyphe
   :outbox "zyphe-outbox"
   :intents "zyphe-outbound-intents"
   :intent-type "ZypheOutboundIntent"
   :event->java schema/ZypheOutboxEvent->java
   :event->pb schema/ZypheOutboxEvent->pb
   :intent->java schema/ZypheOutboundIntent->java
   :pb->intent schema/pb->ZypheOutboundIntent})

(def uniqueness-violation? intent-poller/uniqueness-violation?)

(def transact intent-poller/transact)

(defn save-event [txn event] (intent-poller/save-event txn spec event))

(defn save-intent [txn intent] (intent-poller/save-intent txn spec intent))

(defn intents-with-status
  [config status]
  (intent-poller/intents-with-status (assoc config :store spec) status))
