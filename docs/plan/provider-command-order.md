# Plan: operations reach a provider in the order they were accepted

Implements
[ADR-0033](../adr/0033-operations-reach-a-provider-in-the-order-they-were-accepted.md).

## Context

Provider commands leave from five places, each after its own commit and
on its own topic:

- `cash-account/events.clj` sends open, close and reissue from the
  cash-accounts relay, on the provider's account command channel.
- `payment/events/provider_transfer.clj` mirrors a posting from the
  transactions relay as `transfer-between-accounts`, and the transfer
  sweep resends one whose provider account is not yet open.
- `payment/core.clj` sends `submit-payment` straight after the commit,
  and the outbound sweep republishes a pending one.
- `payment/events/inbound.clj` sends `return-payment` after parking an
  inbound.
- `idv/core.clj` sends `submit-idv-check` after opening a session.

Each adapter saves a command as an intent and drains every pending
intent in creation order, one runner per adapter, with no notion of the
account an intent concerns. A retrying intent waits out its backoff
while later ones for the same account go ahead.

## Decisions

- **Shards.** A fixed four, chosen by a hash of the bank id. Each is a
  log in FDB, `bank-activity-<n>`, written in the transaction that makes
  the change. A log belongs to no record store, so the `fdb` brick gains
  a write that takes the transaction's context rather than an opened
  store.
- **Entries.** A `ChangelogEvent` per change, the payload Avro, keyed by
  bank. A posting reuses `transaction-posted`. The others are new events
  under `schemas/bank-activity/`: `account-opening`, `account-closing`,
  `account-address-rotation-requested`, `outbound-payment-submitted`,
  `inbound-payment-suspended` and `idv-session-opening`, each carrying
  what the command needs as it was at the commit.
- **Reads at handling.** The event processor reads only what never
  changes once written: the bank's providers, its house account and
  1100, and which ledger ids are cash accounts. ADR-0033's part on
  reading is narrowed to say so.
- **Command channel.** One per provider, `<provider>-command`, keyed by
  bank, so a transfer and a later close of either account share a
  partition however the topic is split. ADR-0033 says keyed by bank.
- **Provider accounts.** A Modulr transfer names cash-account ids and
  the adapter resolves each provider account from the open it recorded,
  so a transfer to an account still opening queues behind its open
  rather than waiting for a sweep.
- **Holds at the adapter.** An intent records the accounts it concerns.
  A pass runs an intent only when no earlier intent for any of them is
  still pending; a close or a reissue also waits for every earlier one
  to settle or fail, since the provider refuses either while money is
  still moving.
- **In-place retry.** The event processor retries a failed send in place
  rather than nacking, since a nack replays the partition after the
  messages behind it.

## Slices

Each is a commit on `provider-command-order`, green on its own.

1. **The log and its relay.** `fdb/write-log`; a `bank-activity`
   component with `record` and a `bank-activity/relay` kind starting a
   `changelog-relay` runner per shard; the `bank-activity` topic and its
   DLQ; wiring in `exclusive-dispatchers-service`, the monolith and the
   rigs. Tests: entries come back per shard in commit order and reach the
   topic keyed by bank.
2. **Writers.** The cash-account store, the transaction brick's
   `record`, `submit-outbound`, an inbound parked in suspense and
   `open-session` each record their entry. Nothing consumes them yet.
3. **Event processors and one channel.** The payment brick's
   `activity-event-processor` sends open, close, reissue, mirror
   transfers, submits and returns; the idv brick's sends checks. The old
   senders, the transfer sweep, the outbound republish, the transactions
   relay and the payment brick's transaction event processor go. Each
   adapter takes one `<provider>-command` channel; Modulr resolves a
   transfer's provider accounts itself.
4. **Holds at the adapter.** Each payment adapter's intent gains
   `subjects`, a meta-data version bump; a pass skips an intent held by
   an earlier one; Modulr's close no longer retries a refusal.
5. **Docs and the guard.** The payments, cash-accounts, bank-providers
   and transaction-processing TDDs; ADR-0033's two narrowings;
   `enforce-idioms.sh` refusing a provider command sent outside an
   activity event processor.

## Tests

- **`bank-activity`:** shard choice is stable, entries for one shard
  come back in commit order, and the relay publishes them keyed by bank.
- **`payment`:** the activity processor maps each entry to its command
  and declines what the bank's provider does not take.
- **Adapters:** a held intent is skipped until the one before it
  settles; a close waits behind a transfer for its account.
- **Scenarios:** the PRD journeys on every provider, unchanged, are the
  proof the provider is still asked for everything it was.
