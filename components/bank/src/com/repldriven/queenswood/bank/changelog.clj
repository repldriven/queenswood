(ns com.repldriven.queenswood.bank.changelog
  (:require
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.java.io :as io]))

(def ^:private status-event-name "bank-status-changed")

(def ^:private tier-event-name "bank-tier-changed")

;; Loaded from the classpath rather than the injected `avro/serde`: the
;; payload schema is a property of this brick, and `store.clj` only ever
;; receives a Txn, never the system config the serde arrives in.
(defn- load-schema
  [path]
  (delay (avro/json->schema (slurp (io/resource path)))))

(def ^:private status-schema
  (load-schema "schemas/banks/bank-status-changed.avsc.json"))

(def ^:private tier-schema
  (load-schema "schemas/banks/bank-tier-changed.avsc.json"))

(defn- envelope
  "Wrap a serialised payload in the shared `ChangelogEvent` envelope.
  The dedup key names the field that moved as well as its new value:
  a tier is a free-form label, so one named after a status would
  otherwise share a key with the transition into that status."
  [bank-id event-name field value payload]
  (schema/ChangelogEvent->pb
   (utility/assoc-some
    {:event-id (str (utility/uuidv7))
     :dedup-key (str bank-id ":" field ":" value)
     :event-name event-name
     :payload payload
     :causation-id bank-id
     :ordering-key bank-id
     :created-at (utility/now)}
    ;; Written inside the command's transaction, so this is the
    ;; `process-command` span — which is itself under the request. A
    ;; relay would republish it and the consumer's span would join that
    ;; trace.
    :traceparent
    (telemetry/inject-traceparent))))

(defn status-changed
  "Build the shared-envelope changelog bytes for a bank status
  transition. `changelog` carries `:bank-id`, `:status-before` and
  `:status-after`."
  [{:keys [bank-id status-before status-after]}]
  (let-nom> [payload (avro/serialize @status-schema
                                     {:bank-id bank-id
                                      :status-before status-before
                                      :status-after status-after})]
    (envelope bank-id
              status-event-name
              "status"
              (name status-after)
              payload)))

(defn tier-changed
  "Build the shared-envelope changelog bytes for a bank tier
  transition. `changelog` carries `:bank-id`, `:tier-before` and
  `:tier-after`. A tier change leaves the bank's status alone, so it
  carries its own event name and payload rather than being recorded as
  a status change from a status to itself."
  [{:keys [bank-id tier-before tier-after]}]
  (let-nom> [payload (avro/serialize @tier-schema
                                     {:bank-id bank-id
                                      :tier-before tier-before
                                      :tier-after tier-after})]
    (envelope bank-id tier-event-name "tier" tier-after payload)))
