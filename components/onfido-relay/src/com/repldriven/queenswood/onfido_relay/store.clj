(ns com.repldriven.queenswood.onfido-relay.store
  (:require
    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def spec
  {:adapter :onfido
   :outbox "onfido-outbox"
   :intents "onfido-outbound-intents"
   :intent-type "OnfidoOutboundIntent"
   :event->java schema/OnfidoOutboxEvent->java
   :event->pb schema/OnfidoOutboxEvent->pb
   :intent->java schema/OnfidoOutboundIntent->java
   :pb->intent schema/pb->OnfidoOutboundIntent})

(def uniqueness-violation? intent-poller/uniqueness-violation?)

(def transact intent-poller/transact)

(defn save-event [txn event] (intent-poller/save-event txn spec event))

(defn save-intent [txn intent] (intent-poller/save-intent txn spec intent))

(defn intents-with-status
  [config status]
  (intent-poller/intents-with-status (assoc config :store spec) status))
