(ns com.repldriven.queenswood.zyphe-relay.interface
  "Transactional-outbox egress for the Zyphe adapter. The webhook handler
  persists an `idv-completed` event with `save-event` (co-committed to the
  outbox changelog, relayed to the bus at-least-once), and the
  submit-idv-check consumer persists an outbound intent with `save-intent`
  (the out-of-transaction runner creates the Zyphe verification request)."
  (:require
    [com.repldriven.queenswood.zyphe-relay.system]

    [com.repldriven.queenswood.zyphe-relay.store :as store]))

(defn save-event
  "Persist an `idv-completed` outbox event and append it to the changelog
  in one FDB transaction. Returns the event, or a `:zyphe-outbox/save`
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
  Zyphe call. Returns the intent, or a `:zyphe-outbound/save` anomaly (a
  uniqueness violation when the `dedup-key` was already enqueued).

  Args:
  - txn: an open FDB transaction or `{:record-db :record-store}` config.
  - intent: a map with `:intent-id`, `:dedup-key` (verification-id),
    `:request` (EDN-encoded command data), `:status` (\"pending\"),
    `:attempts`, `:created-at`."
  [txn intent]
  (store/save-intent txn intent))

(defn uniqueness-violation?
  "True if a `save-event`/`save-intent` result is a duplicate-`dedup-key`
  violation (already recorded — safe to treat as accepted)."
  [result]
  (store/uniqueness-violation? result))
