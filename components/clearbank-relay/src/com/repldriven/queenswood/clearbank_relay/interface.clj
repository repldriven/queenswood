(ns com.repldriven.queenswood.clearbank-relay.interface
  "Transactional-outbox egress for the ClearBank adapter. Webhook
  handlers persist an event with `save-event` — co-committed to the
  outbox store's changelog — and the shared
  `changelog-relay/envelope-handler` publishes it to the message bus
  at-least-once. This decouples 'webhook received' from 'downstream
  told' so they cannot diverge. The outbound runner relays each pending
  intent's FPS request with exponential backoff, and fails an intent the
  scheme refuses, or one still failing at its last attempt, into a
  `transaction-rejected` event written to the same outbox."
  (:require
    [com.repldriven.queenswood.clearbank-relay.system]

    [com.repldriven.queenswood.clearbank-relay.store :as store]))

(defn save-event
  "Persist an outbox event and append it to the changelog in one FDB
  transaction. Returns the event, or a `:clearbank-outbox/save` anomaly
  (a uniqueness violation when the `dedup-key` was already recorded — a
  redelivered webhook).

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - event: a map with `:outbox-id`, `:dedup-key`, `:event-name`,
    `:payload` (Avro-serialised bytes), `:correlation-id`,
    `:causation-id`, `:created-at`."
  [txn event]
  (store/save-event txn event))

(defn uniqueness-violation?
  "True if a `save-event` result is a duplicate-`dedup-key` violation,
  i.e. the webhook has already been recorded and can be acked 200
  without re-enqueuing."
  [result]
  (store/uniqueness-violation? result))

(defn save-intent
  "Persist a pending outbound-payment intent — the consume-side outbox
  write — in one FDB transaction. The out-of-transaction outbound relay
  makes the ClearBank HTTP call later. Returns the intent, or a
  `:clearbank-outbound/save` anomaly (a uniqueness violation when the
  `dedup-key` was already enqueued — a redelivered submit-payment).

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - intent: a map with `:intent-id`, `:dedup-key` (end-to-end id),
    `:request` (FPS JSON body), `:status` (\"pending\"), `:attempts`,
    `:created-at`."
  [txn intent]
  (store/save-intent txn intent))
