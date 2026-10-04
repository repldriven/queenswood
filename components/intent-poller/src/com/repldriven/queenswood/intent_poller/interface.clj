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
  - `:event-type` — the outbox event's record type, its dedup key index
    named `<event-type>_by_dedup_key`.
  - `:event->java`, `:event->pb`, `:intent->java`, `:pb->intent`.

  A poller config carries the FDB `:record-db` and `:record-store`, the
  `:schemas` events are serialised with, `:adapter`, the store spec as
  `:store`, the `:default-operation` of an intent whose `:kind` names
  none, the `:delivery-policy` its retries, giving up and breaker take
  (ADR-0034), `:poll-ms`, and optionally `:settles-first?` and
  `:concurrency`. Each pass asks the breaker on `adapter:<adapter>`
  first: open, it calls nothing and fails the intents past their maximum
  age; half-open, it makes one call as the probe; closed, it calls until
  a failure opens it. With `:concurrency` above one, `start` runs that
  many workers, and a closed breaker's pass makes the calls
  `intent-queue/runnable` gives it at once, no two for one subject, so
  up to that many are in flight when a failure opens it. A pass that ran
  an intent is followed at once by the next; an idle one waits
  `:poll-ms`. An answered call through a breaker the pass found closed
  with no failure counted is not recorded, since it changes nothing."
  (:require
    [com.repldriven.queenswood.intent-poller.core :as core]
    [com.repldriven.queenswood.intent-poller.operations :as operations]
    [com.repldriven.queenswood.intent-poller.store :as store]))

(def
  ^{:doc
    "The schema a poller config's `:delivery-policy` and `:poll-ms` are
  checked against, for a runner's `:system/config-schema`."}
  config-schema
  core/config-schema)

;; ---------------------------------------------------------------------------
;; Operations

(defmacro defoperations
  "Register each operation `adapter` carries out, keyed by the `:kind` its
  intents name. Each value is a map of three functions:
  - `:call` — `(fn [config now intent])`, calls the external API and
    returns `[:answered result]`, `[:refused reason]` or
    `[:retry reason]`, or `[:wait reason]` where it made no call and the
    intent is not ready yet. Each call's answer, a refusal included, and
    its failure to answer are recorded on the adapter's breaker; a wait
    is not.
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

(defn advance
  "Keep a pending intent pending with `ctx` as its context for its next
  step, from a fresh attempt count and due at once, `changes` merged in.

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - spec: the store spec.
  - intent-id: the intent.
  - ctx: its next context.
  - changes: fields to set on it, or nil."
  [txn spec intent-id ctx changes]
  (store/advance txn spec intent-id ctx changes))

(defn finish
  "Move an intent still at `status` to `outcome`, with `attempts` where
  given, writing `event` in the same transaction where given and not
  already recorded under its dedup key, as a webhook may have. An intent
  that has moved on is returned unchanged with nothing written. A `sent`
  outcome records when it was sent.

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - spec: the store spec.
  - intent-id, status, outcome: the intent and the move.
  - attempts: the attempt count, or nil to leave it.
  - event: an outbox event, or nil."
  [txn spec intent-id status outcome attempts event]
  (store/finish txn spec intent-id status outcome attempts event))

;; ---------------------------------------------------------------------------
;; Poller

(defn drain-once
  "Attempt each due pending intent once, oldest first, holding one behind
  an earlier intent for its subjects. Each call runs outside any FDB
  transaction, and the write recording its outcome in one of its own.
  Where `config` carries an `:executor`, as `start` gives it for a
  `:concurrency` above one, the calls a closed breaker allows are made
  on it at once. Returns how many intents it ran.

  Args:
  - config: a poller config.
  - now: epoch millis."
  [config now]
  (core/drain-once config now))

(defn start
  "Start a daemon thread that drains `config`'s pending intents, again at
  once after a pass that ran one and after `:poll-ms` otherwise, with
  `:concurrency` workers where it is above one. Returns `{:stop fn}`.

  Args:
  - config: a poller config."
  [config]
  (core/start config))
