# 38. An outbound submit writes no row every payment shares

<!-- tessl-plugin: design -->

## Status

**Accepted**

## Context

The property wanted is that outbound payments in one bank submit
concurrently, as internal payments do since
[ADR-0037](0037-a-control-accounts-balance-is-the-sum-of-the-balances-that-roll-into-it.md).

Two reads stop them. Every submit credits 1200 pending-outbound in the
same transaction that reserves the customer's funds, and every
settlement and reversal debits it, so each rewrites 1200's
`default / pending-outgoing` row. Every submit also reads the bank's
outbound total for the day, `OutboundPayment_sum_amount_by_bank_business_day`,
at serializable isolation to check the daily amount limit, while every
other submit adds to it. On kind at fifty a second, those two keys were
all but every conflict FDB reported: `:payment/submit-outbound` retried
3,546 times and `:payment/settle-outbound` 1,811 times in ten minutes,
achieved fell from 48 a second to 18, and a followed payment took 85
seconds to settle.

1200 is a mirror by construction: a submit debits the customer's
`default / pending-outgoing` bucket by the amount it credits 1200, and a
settlement or reversal clears both. Its balance is the sum of those
buckets with the sides swapped, which the SUM indexes ADR-0037 declared
already hold, grouped by product type and balance status.

The shortlist:

- **Drop 1200's leg from the submit.** Rejected: the leg is the
  customer debit's other side, and `validate-legs` refuses a transaction
  whose debits and credits differ.
- **Shard 1200, or add to it atomically.** Rejected, for the reasons
  ADR-0037 gives for a control.
- **Keep the leg in the journal and write no row for it**, reading 1200's
  balance from the indexes as the mirror of the customers'
  pending-outgoing balances, and read the daily total at snapshot.

## Decision

1200 pending-outbound holds no balance of its own: its balance mirrors
the `default / pending-outgoing` balances of every customer account,
summed across the product types that roll into a control, and its legs
stay in each transaction's journal but write no balance row; the bank's
daily outbound total is read at snapshot, as its count is.

The parts:

- Declare 1200 in `ledger-account`'s `derived` beside the controls, with
  the product types it sums, the `pending-outgoing` status and that it
  mirrors, crediting what they debit.
- Keep 1200's leg in the submit, the settlement and the reversal, and
  pass a transaction's legs through `ledger-account/stored-legs` before
  `balance/apply-legs`, which drops a leg on a mirroring account.
- Read 1200's balance from the indexes wherever one is read, as a
  control's is, and seed no opening row for it.
- Assert after every step of the domain scenario runner that 1200
  mirrors every cash account's pending-outgoing buckets, per currency.
- Read `OutboundPayment_sum_amount_by_bank_business_day` at snapshot.
- The rest of ADR-0037 stands.

## Consequences

Easier:

- An outbound submit writes the customer's rows, the payment and its
  journal, so submits in one bank conflict only where they share a
  debtor account.
- 1200 cannot drift from the reservations it stands for.

Harder:

- The daily amount limit can be passed by the payments in flight at
  once, each having read the total without the others, as the daily
  count already could.
- 1100 is still written by every outbound and inbound settlement, so a
  settlement keeps a row every payment in the bank shares.
- 1200 has no row and no history of its own: what moved it is found
  through the customers' transactions, where its legs still appear.
- A pending-outgoing bucket filed under a product type that rolls into
  no control misstates 1200 silently. The domain scenario runner's
  invariant, asserted after every step, compares 1200 with every cash
  account's pending-outgoing buckets and is the audit that makes it
  visible.
