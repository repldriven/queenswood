# 42. A cash account's balance is the sum of its legs

<!-- tessl-plugin: design -->

## Status

**Proposed**

## Context

The property wanted is that a posting to a cash account reads the
account's balance only where a rule needs it, so a credit commits
without reading anything a payment writes, and a batch that posts to
many accounts conflicts with no payment.

Every posting reads its accounts' balances today. `balance/apply-legs`
reads each `Balance` row a leg moves at serializable isolation and
rewrites it, so any two postings to one account conflict, whichever way
the money moves. Payments to one account are kept apart by their
ordering key, as
[ADR-0041](0041-each-consumer-names-its-performers-and-the-key-that-chooses-them.md)
decides, but a batch is not. On kind, a daily interest run over 1,001 accounts,
with internal payments between the same accounts at fifty a second,
failed 200 of them: capitalising a hundred accounts in one transaction
held it open for between 1.3 and 3 seconds, and two chunks exhausted
their retries on `:fdb/contention` over customers' balance rows.

The rows are a second copy of the journal. Every posting already
records each leg in the `transaction-legs` store, which no record
rewrites, and `TransactionLeg_sum_amount_by_account_bucket_side`, which
[ADR-0039](0039-cash-at-correspondents-balance-is-the-sum-of-its-legs.md)
added, already sums every account's legs by bucket and side by atomic
mutation.
Accrual alone moves a balance without a leg, raising the
`interest-accrued` bucket and its sub-minor carry directly.

The shortlist:

- **Post capitalisation through the payment pipeline, keyed by
  account,** so each account's runs in turn with its payments. Rejected:
  it removes the contention for interest alone, makes the run end when
  its last command is answered rather than when its loop does, and
  leaves every other credit reading the row it adds to.
- **Capitalise a few accounts a transaction.** Rejected: it narrows the
  window in which a payment conflicts with a chunk without closing it.
- **Add to the balance row atomically.** Rejected, as in
  [ADR-0037](0037-a-control-accounts-balance-is-the-sum-of-the-balances-that-roll-into-it.md):
  the Record Layer stores a balance as one record, so changing a field
  means reading it.
- **Sum each bucket of a cash account from its legs,** read from SUM
  indexes on `transaction-legs` that the Record Layer keeps by atomic
  mutation as each leg is saved.

## Decision

A cash account holds no stored balance: each of its buckets is the sum
of the legs recorded against it, read from SUM indexes on
`transaction-legs`, so a posting appends its journal, a debit reads its
account's sum only to check what it may take, and a credit reads
nothing.

The parts, in two stages, each released and load-tested before the next:

- **Stage one, balances.** Every bucket a payment moves.
  - Carry `bank_id` and `product_type` on every `TransactionLeg`, set
    when its posting is recorded, and declare a SUM index on
    `transaction-legs` over `amount`, grouped by `[bank_id,
    product_type, currency, balance_type, balance_status, side]`, at the
    next meta-data version.
  - Read a cash account's buckets from
    `TransactionLeg_sum_amount_by_account_bucket_side` wherever a
    balance is read: the balances routes, the account's embedded
    balances, available balance and every limit.
  - Read a deposit or own-funds control, and 1200 pending-outbound, from
    the new index, in place of ADR-0037's two indexes on `balances`.
  - Stop writing `Balance` rows for a cash account's legs in
    `apply-legs`, and write the rows of 2400, 2500 and 5100, the bank's
    own accounts, as now.
  - Keep a debit's funds check reading its account's sum inside its own
    transaction at serializable isolation, so an overdraft stays
    impossible.
  - Keep the `interest-accrued` bucket a stored row, written by accrual
    and capitalisation as now.
- **Stage two, interest.** A run computed from one read and posted as
  legs.
  - Compute every account's accrual and capitalisation from one snapshot
    read of the bank's accounts and their sums at the run's cut-off,
    then append each account's legs and run row, a hundred accounts a
    transaction, reading nothing a payment writes.
  - Record a chunk's accrual as one transaction per currency, a leg on
    each account's `interest-accrued` bucket and the opposite on 5100
    interest expense, and its capitalisation as one transaction per
    account from that bucket to the account's default one.
  - Read 2400 interest payable as the sum of the customers'
    `interest-accrued` buckets, through the legs' index by product type,
    and 5100 as the sum of its own legs, as 1100 is, so no chunk writes
    a row another chunk or a payment reads.
  - Carry the sub-minor remainder on the account's `InterestAccountRun`
    row as the change the accrual made to it, in place of the balance
    row's `credit_carry`, and read the carry an accrual opens with as
    the sum of those changes, from a SUM index on the rows.
  - Retire the `interest-accrued` rows once nothing reads them.
- **Throughout.**
  - Keep a leg's amount in whole minor units: the accrual's sub-minor
    remainder is the interest run's own state, never a balance.
  - Never delete a leg that a balance sums without first appending one
    that restates its sum, so archiving a period closes it with a
    carry-forward leg.
  - The rest of ADR-0037 and ADR-0039 stands.

## Consequences

Easier:

- A credit to a cash account commits without reading it, so inbound
  settlement and interest never conflict with a payment, and a run
  posts a hundred accounts a transaction beside any payment load.
- An account's balance is its journal by construction: no balance moves
  without a leg, and no row can drift from the legs that explain it.
- Every movement of a balance stays on record; nothing is overwritten.
- One mechanism serves every derived balance, customer and control,
  where ADR-0037 and ADR-0039 had two.

Harder:

- A debit still reads its account's sum, so a credit committed to that
  account between the debit's read and its commit makes the debit
  retry. The retry is a short transaction, where today the batch is the
  one that fails.
- A balance at a past date is not in the index: it is the sum of the
  account's legs up to that date, a scan, so a statement over a
  long-lived account wants a closing figure per period.
- Deleting a leg lowers its account's sum, so retention needs the
  carry-forward rule rather than a plain purge.
- Every leg saved updates two SUM indexes, atomic mutations rather than
  reads, and stage two adds a leg per account per day of accrual.
- Legs saved before stage one carry no `bank_id` or `product_type`, so
  an instance holding them has its legs back-filled before the new index
  is built, and a store past a few hundred legs opens with the index
  disabled until an `OnlineIndexer` builds it, as
  [schema-evolution](../recipes/code/schema-evolution.md) records.
- A leg stamped with the wrong product type misstates a control
  silently. The trial balance and the control-against-sub-ledger
  invariant, asserted after every step of both scenario runners, read
  the control from the legs' index by stamped product type and the
  sub-ledger by account, and are the audit that makes it visible.
