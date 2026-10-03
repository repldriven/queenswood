(ns com.repldriven.queenswood.intent-poller.interface
  "An external adapter's intents: the store of intents and outbox events,
  and the poller that carries out each pending intent's operation against
  the external API outside any FDB transaction, in the order
  `intent-queue` keeps (ADR-0033). An adapter registers each operation
  it carries out with `defoperations`; the poller owns the attempt count, the
  backoff, giving up, the outcome's event, and failing an intent whose
  call throws.

  A store spec names the adapter's stores and their protobuf
  conversions:
  - `:adapter` — a keyword, also the prefix of the store's anomaly
    categories.
  - `:outbox`, `:intents` — the record store names.
  - `:intent-type` — the intent's record type, its status index named
    `<intent-type>_by_status`.
  - `:event->java`, `:event->pb`, `:intent->java`, `:pb->intent`.

  A poller config carries the FDB `:record-db` and `:record-store`, the
  `:schemas` events are serialised with, `:adapter`, the store spec as
  `:store`, the `:default-operation` of an intent whose `:kind` names
  none, and optionally `:max-attempts` (20), `:initial-backoff-ms`
  (1000), `:max-backoff-ms` (60000), `:poll-ms` (200) and
  `:settles-first?`."
  (:require
    [com.repldriven.queenswood.intent-poller.core :as core]
    [com.repldriven.queenswood.intent-poller.operations :as operations]
    [com.repldriven.queenswood.intent-poller.store :as store]))

;; ---------------------------------------------------------------------------
;; Operations

(defmacro defoperations
  "Register each operation `adapter` carries out, keyed by the `:kind` its
  intents name. Each value is a map of three functions:
  - `:call` — `(fn [config now intent])`, calls the external API and
    returns `[:answered result]`, `[:refused reason]` or
    `[:retry reason]`.
  - `:answered` — `(fn [config now intent result])`, returns
    `{:status status :event descriptor}`, the status the intent ends at
    and the event it reports, or nil for none.
  - `:failed` — `(fn [config now intent failure reason])`, the event
    descriptor a failed intent reports, or nil; `failure` is `:refused`
    or `:undelivered`.

  An event descriptor is `{:event-name :dedup-key :data}`, `:data`
  serialised with the config's schema for `:event-name`.

  Usage:
    (defoperations :adapter {\"check\" {:call check :answered checked :failed failed}})"
  [adapter operation-map]
  `(operations/defoperations ~adapter ~operation-map))

;; ---------------------------------------------------------------------------
;; Store

(def ^{:doc "Run `f` in an FDB transaction, as `fdb/transact`."} transact
  store/transact)

(defn uniqueness-violation?
  "True where a `save-event` or `save-intent` result is a duplicate
  `dedup-key`, already recorded and safe to treat as accepted."
  [result]
  (store/uniqueness-violation? result))

(defn save-event
  "Persist an outbox event and append it to the outbox's changelog in one
  transaction. Returns the event, or a `:<adapter>-outbox/save` anomaly.

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - spec: the store spec.
  - event: `:outbox-id`, `:dedup-key`, `:event-name`, `:payload`,
    `:correlation-id`, `:causation-id`, `:created-at`."
  [txn spec event]
  (store/save-event txn spec event))

(defn save-intent
  "Persist an outbound intent. Returns it, or a `:<adapter>-outbound/save`
  anomaly, a uniqueness violation where its `dedup-key` is enqueued.

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - spec: the store spec.
  - intent: the intent record."
  [txn spec intent]
  (store/save-intent txn spec intent))

(defn intents-with-status
  "Every intent of `config`'s store at `status`, or an anomaly.

  Args:
  - config: a poller config.
  - status: `\"pending\"`, `\"sent\"`, `\"settled\"` or `\"failed\"`."
  [config status]
  (core/intents-with-status config status))

;; ---------------------------------------------------------------------------
;; Poller

(defn drain-once
  "Attempt each due pending intent once, oldest first, holding one behind
  an earlier intent for its subjects. Each call runs outside any FDB
  transaction, and the write recording its outcome in one of its own.

  Args:
  - config: a poller config.
  - now: epoch millis."
  [config now]
  (core/drain-once config now))

(defn start
  "Start a daemon thread that drains `config`'s pending intents every
  `:poll-ms`. Returns `{:stop fn}`.

  Args:
  - config: a poller config."
  [config]
  (core/start config))
