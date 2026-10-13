# 48. Suspense's balance is the sum of its legs

<!-- tessl-plugin: design -->

## Status

**Accepted**

## Context

The property wanted is that every balance in a bank's books is held
one way, as the sum of the legs recorded against it, so no posting
rewrites a balance row and nothing has to know which accounts keep one.

[ADR-0037](0037-a-control-accounts-balance-is-the-sum-of-the-balances-that-roll-into-it.md),
[ADR-0038](0038-an-outbound-submit-writes-no-row-every-payment-shares.md),
[ADR-0039](0039-cash-at-correspondents-balance-is-the-sum-of-its-legs.md)
and
[ADR-0042](0042-a-cash-accounts-balance-is-the-sum-of-its-legs.md)
each moved an account whose row every payment in a bank read and
rewrote. 2500 suspense is posted to only when an inbound payment cannot
be applied and when it is sent back, so its row was never contended,
and each of those decisions kept it stored.

It is the last. A posting that names 2500 is the only one whose leg
`balance/accept-legs` still writes to a row, and the balance brick
carries a second way to read an account and a row rewrite for that one
account, which every description of a posting has to except.

Every posting to 2500 records its legs in the `transaction-legs` store,
and `TransactionLeg_sum_amount_by_bank_account_bucket_side`, which 1100
and 5100 are read from, is keyed by account, so it already sums them.

The shortlist:

- **Leave 2500 stored.** Rejected: it avoids no contention, and keeps
  two ways of holding a balance alive for one account.
- **Sum 2500's legs from the index 1100 and 5100 are read from.**

## Decision

2500 suspense holds no balance of its own: its balance is the sum of
the legs recorded against it, read as 1100's is, and its legs stay in
each transaction's journal but are left out of the balance writes.

The parts:

- Declare 2500 in `ledger-account`'s `derived` as summing its own legs,
  read wherever a ledger account's balance is read, the close guard
  inside its own transaction, and seed no opening row for it.
- Make a leg's `product_type` required: its cash account's, or
  `general-ledger` on a ledger account, so a leg that says neither is
  not recorded.
- Hand `balance/accept-legs` a recorded posting's every leg, and have
  it take those of a cash account's product type and pass over the
  `general-ledger` ones, in place of a `ledger-account` call that left
  the ledger accounts' legs out first.
- Open a bucket under its leg's product type and no other.
- Start loading, at a posting's `ledger-account/prefetch`, only the
  ledger accounts the posting names.
- Leave the row seeded before this unread.
- The rest of ADR-0037, ADR-0038, ADR-0039 and ADR-0042 stands.

## Consequences

Easier:

- No account in the chart keeps a stored balance, so a balance is read
  one way, and a posting writes no balance row but the one marking a
  cash account's bucket open.
- A posting hands the same legs to `ledger-account/accept-legs` and to
  `balance/accept-legs`, each taking the ones that are its own, and
  reads no ledger account it does not post to.
- What `balance/accept-legs` does for every leg it takes is check.
- 2500's balance is its journal by construction, so a posting cannot
  move it without a leg that says why.

Harder:

- A posting to 2500 is held to no balance capability or balance limit,
  as one to 1100 is not. A rule on what suspense may hold needs a check
  of its own.
- A bank created before this keeps a 2500 row nothing reads.
- A leg on a cash account tagged `general-ledger` is passed over
  unchecked. Its control's sum groups by product type and leaves it out
  too, so the trial balance stops tying.
- Every ledger leg is one more entry in the index that sums legs by
  product type, in a group nothing reads.
- An index that sums the wrong legs misstates 2500 silently. The trial
  balance asserted after every step of both scenario runners, which
  reads 2500 from the index, is the audit that makes it visible.
