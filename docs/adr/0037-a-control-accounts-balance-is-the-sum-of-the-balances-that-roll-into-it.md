# 37. A control account's balance is the sum of the balances that roll into it

<!-- tessl-plugin: design -->

## Status

**Accepted**

## Context

The property wanted is that internal payments in one bank commit
concurrently, so the payment command topic can take more partitions and
`financial-processors-service` more replicas, each adding throughput.

Every posting prevents it today. `add-control-legs` follows each posted
default leg on a cash account with a same-side leg on its product type's
control — 2100, 2200, 2300 or 3100 — and `apply-legs` reads that
control's `default / posted` row at serializable isolation and rewrites
it, so two internal payments in one bank and currency conflict on one
row whichever accounts they move. On kind, with the payment command
topic at two partitions and a replica on each, at fifty a second, 176 of
the 193 conflicting keys FDB reported were that row of 2100 and the rest
creditor accounts, 12 payments ran out of attempts and returned 500, and
a command took 45 ms rather than 19, so two consumers handled fewer
payments than one. The chart already spares 2400 the same contention by
posting it in aggregate at the close of an interest run, and the deposit
controls are the rows it left in every payment.

The [chart of accounts](../tdd/chart-of-accounts.md) rejected deriving
the whole general ledger from customer balances, since income, expense
and equity have no customer leg. A control is the one kind of ledger
account whose balance is, by definition, its sub-ledger's sum.

The shortlist:

- **Mirror the control in a later step.** Rejected: the control lags its
  sub-ledger, the trial balance does not tie between the posting and the
  mirror, and the step that mirrors serialises on the same row.
- **Shard each control into several rows.** Rejected: the conflicts are
  spread rather than removed, and every reader sums the shards.
- **Add to the control row atomically.** Rejected: the Record Layer
  stores a balance as one record, so changing a field means reading it.
- **Derive the control from an aggregate index** over the sub-ledger's
  balance rows, which the Record Layer maintains by atomic mutation.

## Decision

A deposit or own-funds control account holds no balance of its own: its
balance is the sum of the `default / posted` balances of the cash
accounts whose product type maps to it, read from SUM indexes the Record
Layer keeps by atomic mutation as each of those rows is saved, so a
posting writes only the accounts its legs name.

The parts:

- Declare two SUM indexes on `balances`, one over `credit` and one over
  `debit`, each grouped by `[bank_id, product_type, currency,
  balance_type, balance_status]`, at the next meta-data version.
- Store every cash account's balance rows under that account's product
  type: a bucket a leg opens takes the leg's product type, else that of
  the account's existing buckets, and is labelled general ledger only on
  an account with neither.
- Remove `add-control-legs`, the `:control` flag and its rule in
  `validate-legs`: a posting's legs are its journal and nothing more.
- Keep the control gate: a posting resolves the control each of its
  product types maps to, reading the `LedgerAccount` record, and refuses
  `:gl/missing-currency-account` or `:ledger-account/closed` as now.
- Read a control's balance from the indexes wherever one is read: the
  balances route, the list route's `:posted-balance` and trial balance,
  and the `:gl/non-zero-on-close` guard, the guard inside its
  transaction and every other reader at snapshot.
- Capitalise interest account by account: each account's transaction
  debits 2400 and credits the customer's `default / posted`, lowering its
  `interest-accrued` bucket by a balance write as accrual raises it, and
  the run's closing debit of 2400 against the deposit control goes.
- Keep 2400, 1100, 1200, 2500 and 5100 as stored balances, and accrual
  as it is, one debit of 5100 against 2400 at the close of each run.
- Seed no balance rows for the four controls in `new-bank`, and leave
  the rows seeded before this unread.
- Keep both standing invariants. The second now compares a control read
  from the indexes, grouped by each balance row's product type, against
  its sub-ledger summed by scanning cash accounts, grouped by each
  account's, so it checks the rows carry their account's product type.

## Consequences

Easier:

- An internal payment writes the two customer rows it moves and nothing
  else, so concurrency between payments is bounded by the accounts they
  share, which the debtor account's ordering key already keys.
- A control cannot drift from its sub-ledger through a missed or
  doubled leg; what can still go wrong is a balance row filed under the
  wrong product type, which the second invariant names.
- A posting carries only its journal legs, so the transaction record,
  the `transaction-posted` event and `validate-legs` lose their control
  legs and the rule that policed them.

Harder:

- Every save of a cash account's balance row updates both indexes, two
  atomic mutations more per row.
- A store already past a few hundred balance rows opens with the indexes
  disabled until an `OnlineIndexer` builds them, and the migrator runs
  none, as [schema-evolution](../recipes/code/schema-evolution.md)
  records.
- A control's balance has no row and no history of its own: what moved
  it is found through the sub-ledger's transactions.
- Every account's capitalisation now writes 2400, so a run that commits
  accounts concurrently contends on it. Payments never write 2400.
- 1100 is still written by every inbound and outbound settlement, and
  1200 and the serializable `OutboundPayment` SUM by every outbound
  submit, so those paths keep a row that every payment in the bank
  shares.
- An index that sums the wrong rows misstates a control silently. The
  two invariants asserted after every step of both scenario runners are
  the audit that makes it visible.
