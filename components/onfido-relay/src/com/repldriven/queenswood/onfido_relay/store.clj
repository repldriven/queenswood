(ns com.repldriven.queenswood.onfido-relay.store
  (:require
    [com.repldriven.queenswood.intent-poller.interface :as intent-poller]
    [com.repldriven.queenswood.schema.interface :as schema]

    [clojure.edn :as edn]))

(def ^:private kept
  "What a settled or failed intent's request keeps: the ids and what was
  asked for, and nothing else the provider was given (ADR-0045)."
  [:bank-id :verification-id :party-id :session-id :channel :verifications
   :screenings])

(defn- redact
  [request]
  (pr-str (select-keys (edn/read-string request) kept)))

(def spec
  {:adapter :onfido
   :outbox "onfido-outbox"
   :intents "onfido-outbound-intents"
   :intent-type "OnfidoOutboundIntent"
   :event-type "OnfidoOutboxEvent"
   :event->java schema/OnfidoOutboxEvent->java
   :event->pb schema/OnfidoOutboxEvent->pb
   :intent->java schema/OnfidoOutboundIntent->java
   :pb->intent schema/pb->OnfidoOutboundIntent
   :redact redact})

(def uniqueness-violation? intent-poller/uniqueness-violation?)

(def transact intent-poller/transact)

(defn save-event [txn event] (intent-poller/save-event txn spec event))

(defn save-intent [txn intent] (intent-poller/save-intent txn spec intent))

(defn intents-with-status
  [config status]
  (intent-poller/intents-with-status (assoc config :store spec) status))
