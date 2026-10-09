(ns com.repldriven.queenswood.zyphe-relay.store
  (:require
    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.transit.interface :as transit]))

(def ^:private kept
  "What a settled or failed intent's request keeps: the ids and what was
  asked for, and nothing else the provider was given (ADR-0045)."
  [:bank-id :verification-id :party-id :session-id :channel :verifications
   :screenings])

(defn- redact
  [request]
  (transit/write-str (select-keys (transit/read-str request) kept)))

(def spec
  {:adapter :zyphe
   :outbox "zyphe-outbox"
   :intents "zyphe-outbound-intents"
   :intent-type "ZypheOutboundIntent"
   :event-type "ZypheOutboxEvent"
   :event->java schema/ZypheOutboxEvent->java
   :event->pb schema/ZypheOutboxEvent->pb
   :intent->java schema/ZypheOutboundIntent->java
   :pb->intent schema/pb->ZypheOutboundIntent
   :redact redact})

(def uniqueness-violation? intent-poller/uniqueness-violation?)

(def transact intent-poller/transact)

(defn save-event [txn event] (intent-poller/save-event txn spec event))

(defn save-intent [txn intent] (intent-poller/save-intent txn spec intent))

(defn intents-with-status
  [config status]
  (intent-poller/intents-with-status (assoc config :store spec) status))
