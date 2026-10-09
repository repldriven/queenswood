(ns com.repldriven.queenswood.bank-activity.interface
  "A bank's activity: every change a provider must act on, recorded in
  the transaction that makes it and read back in the order those
  transactions committed (ADR-0033).

  Banks are spread over a fixed number of shards by id, each shard a log
  of its own. The `bank-activity/relay` kind tails every shard's log and
  publishes each entry, keyed by bank, through the handler it is given,
  so a bank's entries reach a consumer in commit order. Changing the
  number of shards moves banks between logs, which is a migration."
  (:require
    [com.repldriven.queenswood.bank-activity.system]

    [com.repldriven.queenswood.bank-activity.core :as core]))

(def ^{:doc "How many logs a bank's activity is spread over."} shard-count
  core/shard-count)

(defn log-name
  "The name of the log `bank-id`'s activity is recorded on."
  [bank-id]
  (core/log-name bank-id))

(defn record
  "Record an activity entry for a bank in `txn`, on its shard's log.
  Returns nil, or an anomaly.

  Args:
  - txn: the transaction making the change the entry records.
  - entry: a map of
    - `:bank-id`: the bank, which keys the entry when it is published.
    - `:event-name`: an activity event — `cash-account-open-requested`,
      `cash-account-close-requested`, `cash-account-address-rotation-requested`,
      `outbound-payment-submitted`, `inbound-payment-suspended`,
      `idv-session-open-requested` or `transaction-posted`. Any other name is
      `:bank-activity/unknown-event`.
    - `:data`: the event's payload, as it is at this commit; `:bank-id`
      is added to it.
    - `:causation-id`: the id of the record the change was made to.
    - `:dedup-key`: what identifies the change, unique per event name."
  [txn entry]
  (core/record txn entry))

(defn send-command
  "Send `command`, a command envelope, on `channel` keyed by `bank-id`,
  for a consumer of a bank's activity answering an entry. A failed send
  is tried again here, a few times with a growing wait, rather than left
  for the entry's redelivery, which would let the bank's later entries
  pass it. Returns what the last send returned."
  [bus channel bank-id command]
  (core/send-command bus channel bank-id command))
