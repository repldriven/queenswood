(ns com.repldriven.queenswood.onfido-relay.interface
  "Transactional-outbox egress for the Onfido adapter. The
  submit-idv-check consumer persists an outbound intent with
  `save-intent`, and the `onfido-relay/outbound-runner` component drains
  them outside any transaction: it resumes the workflow run the
  verification already has waiting on the person, or creates an
  applicant and a run on the smallest configured workflow covering the
  verifications and screenings asked for, tagged with the bank and
  verification ids, and records the run's link as `idv-session-opened`.
  It refuses to start while the configured workflows do not cover what
  the provider's declaration says. The webhook handler reads a finished
  run back with `read-run` and persists its evidence with `save-event`,
  co-committed to the outbox changelog and relayed to the bus
  at-least-once."
  (:require
    [com.repldriven.queenswood.onfido-relay.system]

    [com.repldriven.queenswood.onfido-relay.outbound :as outbound]
    [com.repldriven.queenswood.onfido-relay.store :as store]))

(defn save-event
  "Persist an outbox event and append it to the changelog
  in one FDB transaction. Returns the event, or an `:onfido-outbox/save`
  anomaly (a uniqueness violation when the `dedup-key` was already
  recorded — a redelivered webhook).

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - event: a map with `:outbox-id`, `:dedup-key`, `:event-name`,
    `:payload` (Avro bytes), `:correlation-id`, `:causation-id`,
    `:created-at`."
  [txn event]
  (store/save-event txn event))

(defn save-intent
  "Persist a pending submit-idv-check intent — the consume-side outbox
  write — in one FDB transaction. The out-of-transaction runner makes the
  Onfido calls. Returns the intent, or an `:onfido-outbound/save` anomaly
  (a uniqueness violation when the `idempotency-key` was already enqueued).

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - intent: a map with `:intent-id`, `:idempotency-key` (the session id),
    `:request` (EDN-encoded command data), `:status` (\"pending\"),
    `:attempt-count`, `:created-at`."
  [txn intent]
  (store/save-intent txn intent))

(defn uniqueness-violation?
  "True if a `save-event`/`save-intent` result is a duplicate-`dedup-key`
  violation (already recorded — safe to treat as accepted)."
  [result]
  (store/uniqueness-violation? result))

(defn read-run
  "The workflow run `run-id` as Onfido holds it, with every report of its
  applicant's checks: `{:run :bank-id :verification-id :reports}`, the
  ids read off the run's tags. An `:idv/*` failure where Onfido cannot
  be read.

  Args:
  - config: `{:onfido-url :api-token}`.
  - run-id: the workflow run's id."
  [config run-id]
  (outbound/read-run config run-id))

(defn clear-personal-data
  "Reduce every settled or failed intent's request to what the store
  spec's `:redact` keeps, and clear the payload of every `idv-evidence`
  outbox entry, which the outbox changelog relays and nothing reads
  again (ADR-0045). Returns `{:intents :payloads}`, how many of each it
  cleared — none on a rerun — or an anomaly.

  Args:
  - config: `{:record-db :record-store}`."
  [config]
  (store/clear-personal-data config))
