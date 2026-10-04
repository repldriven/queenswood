# 39. Cash at correspondent's balance is the sum of its legs

<!-- tessl-plugin: design -->

## Status

**Accepted**

## Context

The property wanted is that settlements in one bank commit
concurrently, so the outbound settlement consumer can take a second
partition, as the payment commands did under
[ADR-0037](0037-a-control-accounts-balance-is-the-sum-of-the-balances-that-roll-into-it.md)
and outbound submits under
[ADR-0038](0038-an-outbound-submit-writes-no-row-every-payment-shares.md).

Every inbound settlement debits 1100 cash-at-correspondent and every
outbound settlement and return credits it, so each reads and rewrites
1100's `default / posted` row. On kind at fifty outbound payments a
second, the settlement consumer is 89% busy on one partition, settling
in 0.7 seconds at p50 and 7.6 at p95, and a second partition would make
two settlements contend on that row.

1100 is not a mirror. Inbound and outbound settlements move it with a
customer balance, but interest capitalisation credits customers from
2400 and own funds arrive through it, so no set of customer balances
sums to it, and the balances store's indexes cannot read it.

Every posting already records each of its legs in the
`transaction-legs` store, keyed by account, and records are never
rewritten, so two postings inserting legs share no key.

The shortlist:

- **Post 1100 in aggregate** at intervals, as accrual posts 2400 and
  5100 at the close of a run. Rejected: the books would not balance
  between a settlement and the next posting, which, unlike a run's
  window, is open whenever money moves, and the invariants asserted
  after every scenario step would fail.
- **Shard 1100 into several rows.** Rejected, for the reasons ADR-0037
  gives for a control.
- **Sum 1100's legs from an aggregate index** on `transaction-legs`,
  which the Record Layer maintains by atomic mutation as each leg is
  saved.

## Decision

1100 cash-at-correspondent holds no balance of its own: its balance is
the sum of the legs recorded against it, read from a SUM index on
`transaction-legs`, and its legs stay in each transaction's journal but
are left out of the balance writes.

The parts:

- Declare `TransactionLeg_sum_amount_by_account_bucket_side` on
  `transaction-legs`, summing `amount` grouped by `[account_id,
  balance_type, balance_status, side]`, at meta-data version 75.
- Read an account's summed `{:credit :debit}` through
  `transaction/sum-legs`.
- Declare 1100 in `ledger-account`'s `derived` as summing its own legs,
  read wherever a ledger account's balance is read, and seed no opening
  row for it.
- Leave 1100's legs out of `balance/apply-legs` through
  `ledger-account/stored-legs` at every posting that names it on the
  payment path: every inbound posting, from suspense to return, and
  outbound settlement, reversal and return.
- The rest of ADR-0037 and ADR-0038 stands.

## Consequences

Easier:

- A settlement writes the customer's rows, the payment and its journal,
  so settlements in one bank conflict only where they share an account.
- 1100's balance is its journal by construction, so a posting cannot
  move it without a leg that says why.

Harder:

- Every leg saved updates the index, an atomic mutation more per leg,
  on every account and not only 1100.
- A store already past a few hundred legs opens with the index disabled
  until an `OnlineIndexer` builds it, and the migrator runs none, as
  [schema-evolution](../recipes/code/schema-evolution.md) records, so a
  deployed instance cannot read 1100 until one has.
- A posting through `transaction/record-and-post`, which the simulated
  inbound transfer takes, still writes 1100's old row: leaving it out
  would need `transaction` to call `ledger-account`, which reads from
  `transaction`. Nothing reads that row.
- Raising the settlement topic's partitions needs the payment adapters'
  outbox entries to carry an ordering key first, since an unkeyed
  publish reorders a payment's events across partitions.
- An index that sums the wrong legs misstates 1100 silently. The trial
  balance asserted after every step of both scenario runners, which
  reads 1100 from the index, is the audit that makes it visible.
