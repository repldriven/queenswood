# Product limits

> **Status: proposal.** The policy engine, its limits on a bank's
> payments and on an account's available balance, and a product
> version's interest and reward terms exist. A product version carries
> no limit. The Proposed Solution is the build list, and
> [First slice](#first-slice) says what comes first.

## Objective

Let a product version bound the payments into and out of the accounts
opened under it, and what those accounts may hold: a regular saver that
takes at most 300 a month, a fixed-rate saver that allows one
withdrawal a year, an account with an arranged overdraft.

In scope: the limits a version carries, the windows and anchors a limit
is counted over, how an account's payments are counted over a period,
and where each payment path checks them.

Out of scope: a rate or a reward that depends on what the customer pays
in or takes out, which [interest](interest.md) holds open; the limits a
tier or the platform sets on a bank, which
[policy-evaluation](policy-evaluation.md) decides; and moving accounts
onto a version with other limits, which
[cash-account-migration](cash-account-migration.md) decides.

## Background

- **Limits are the policy engine's.** `policy/check-limit` takes the
  policies in force, a kind and a request carrying the aggregate the
  caller read, and refuses `:policy/limit-exceeded`. A `Limit` in
  [limit.proto](/components/schema/resources/schemas/policy/limit.proto)
  bounds an amount or a count by a minimum, a maximum or a range, over
  a `TimeWindow` of an instant, a day, a week, a month or a rolling
  span. The engine computes no window: the caller reads the aggregate
  for the window the limit names.
- **Payment limits are per bank.** The `internal-payment`,
  `outbound-payment` and `inbound-payment` kinds are counted per bank
  and business day, from count and sum indexes on each payment store,
  in `payment`'s `get-internal-aggregates`, `get-outbound-aggregates`
  and `get-inbound-aggregates`. Nothing counts one account's payments.
- **The balance limit is the platform's.** `balance/accept-legs`
  checks the `balance` kind's computed `available` bound on each
  account a posting names. Every caller hands it the platform's
  policies, which hold one such limit: available stays non-negative on
  internal, inbound, outbound and reward postings, and an account
  already below may improve.
- **A version's terms.** `CashAccountProduct` in
  [cash-account-product.proto](/components/schema/resources/schemas/cash-account-product/cash-account-product.proto)
  carries `interest_terms` and `reward_terms`. An interest band's
  lowest balance says which rate applies above an amount and refuses
  nothing.
- **A payment's status.** An outbound payment is `pending`, `held`,
  `completed`, `failed` or `returned`, and an inbound one `admitted`,
  `held`, `settled`, `suspended`, `returned` or `return-failed`. An
  internal payment has no status: it is recorded complete.

## Proposed Solution

### Limits a version carries

`CashAccountProduct` gains `repeated policy.Limit limits`, the policy
engine's own message, beside `interest_terms` and `reward_terms`. A
version's limits are fixed when it is published, as its other terms
are. `AccountType`, which the policy schemas share with cash accounts
and payee checks, moves to a folder of its own, since the product's
schema now reads the policy's and a cash account's reads the product's.

`cash-account-product`'s `domain.clj` accepts two kinds on a version
and refuses any other, `:cash-account-product/invalid-limit`:

- `account-payment`, a new kind in `limit.proto`, whose filter names a
  `PaymentDirection` of `in` or `out`. It bounds the amount or the
  count of the payments in that direction over the limit's window.
- `balance`, the existing kind, bounding the computed `available`
  balance.

A payment path reads the account's version and hands its limits to
`policy/check-limit` as one more policy beside the bank's, so a refusal
is `:policy/limit-exceeded` with the limit's reason, and a version's
limits and a tier's are one mechanism.

### Windows and anchors

`TimeWindow` gains `quarterly` and `annually`. `AmountAggregate` and
`CountAggregate` gain a `WindowAnchor`, and `CountAggregate.value`
becomes required, so a count of zero, no withdrawals, is kept:

- `account`, the default for an account's limit, starts a period on the
  day the account's version started for it, `version_from_on`, which is
  the day it was opened until a migration moves it.
- `calendar` starts it on the first of the month, quarter or year, or
  on the `starts_month` and `starts_day_of_month` the aggregate names,
  as a tax year does, and a week on a Monday.

`policy` gains `periods`, the periods the limits matching a request are
counted over, and `period-start`, a pure function of a period, a
business day and the account's day, which returns the first day of the
period the business day falls in. A request to `check-limit` that names
a `:period` is checked against the limits counted over exactly that
period. `limits->policy` wraps a version's limits as a live policy.

### Counting an account's payments

Each payment store gains a sum of `amount` and a count, grouped by
account, business day and status, per side:

- `InternalPayment` by `[bank_id, debtor_account_id, business_day]`
  and by `[bank_id, creditor_account_id, business_day]`.
- `OutboundPayment` by
  `[bank_id, debtor_account_id, business_day, status]`.
- `InboundPayment` by
  `[bank_id, creditor_account_id, business_day, status]`.

A payment's save moves it between groups when its status changes, so
nothing is compensated by hand. `payment-query` gains
`get-account-aggregates`, which reads one account's groups from the
period's first day to the business day, one range read per index, and
returns the count and the sum of the payments that count:

- Out: every internal payment the account is debtor of, and an
  outbound payment that is `pending`, `held`, `completed` or
  `returned`. A withdrawal counts from its submission, one that fails
  before it settles gives its allowance back, and one returned after it
  settled does not.
- In: every internal payment the account is creditor of, and an
  inbound payment that is `admitted` or `settled`. A held one counts
  when it is released, since its release is checked as a new payment
  is.

A transfer between two accounts of one customer is a payment out of one
and into the other. `fdb`'s group read takes a first and a last key
beside its prefix.

### Checking a payment

`payment`'s `account_limits.clj` reads the limits an account's version
carries, kept in the processor's cache since a published version never
changes. Where a limit bounds the account's payments in the payment's
direction, its `get-aggregates` reads that account's period
serializably, and the domain's `check-account-payment` checks the limit
beside the bank's:

- an internal payment checks the debtor's `out` limits and the
  creditor's `in` limits;
- an outbound submission checks the debtor's `out` limits;
- `check-inbound-acceptance` checks the creditor's `in` limits at
  settlement, at a hold's release and at admission, and an inbound
  payment it refuses is parked in suspense and returned, as its other
  refusals are.

An account whose version carries no such limit reads nothing more than
it does today.

### The balance floor and ceiling

A product request states `min-balance` and `max-balance`, and the
brick turns them into `balance` limits on the computed `available`
balance, each allowing an account already outside it to improve. Every
version carries a minimum, zero where none is stated, on internal,
inbound, outbound and reward postings; a maximum, where one is stated,
bounds internal and inbound ones. Interest, whose transaction types
neither names, is never refused, and a negative minimum is an arranged
overdraft. A maximum at or below the minimum is refused,
`:cash-account-product/invalid-limit`.

`balance/accept-legs` takes `:account-policies`, a map of account id
to the policies one account alone is held to, and checks each
account's legs against them beside the policies it is given. The
internal payment, the outbound submission and an inbound payment's
settlement and release pass each account's version's limits, which
`account-limits/version-policies` builds.

A payment from outside is refused before it is recorded, so a maximum
is checked at its acceptance too: where a limit caps the creditor's
balance, `account-limits/get-aggregates` reads its available balance
serializably and `check-inbound-acceptance` checks the payment against
the cap, parking one that passes it in suspense.

The platform's non-negative limit stays until the scenario model, which
reads its floor from that limit, takes an account's from its product.
Then each version's minimum of zero replaces it, and a reward's
posting passes the house account's and the customer's limits as a
payment does.

### The API

A product request and a version's body carry `payment-limits`, each a
`direction` of `in` or `out`, a `measure` of `amount` or `count`, a
`max`, a `period` of `payment`, `day`, `week`, `month`, `quarter` or
`year`, and optionally an `anchor` of `account` or `calendar`, a
`starts-month`, a `starts-day-of-month` and a `reason`. The brick turns
each into an `account-payment` limit, as it turns `interest-rate-bps`
into interest terms, and gives one without a reason a default. They
carry `min-balance`, which a version's body always returns, and
`max-balance`, each in minor units of the version's currency.

### First slice

Built: the `account-payment` kind, the new windows and anchors, a
version's limits stored, validated and returned, the per-account
indexes, the checks on the three payment paths, and the balance
minimum and maximum beside the platform's limit. Next: the model's
floor taken from the product, the platform's limit removed, and a
reward's posting held to its accounts' limits.

### Tests

- **`policy`** — `period-start` for each window under each anchor, and
  an `account-payment` limit matched by direction.
- **`cash-account-product`** — a version's limits kept through draft,
  update and publish, and a limit of another kind refused.
- **`test-api-scenarios`** — a product's payment limits returned as
  stated and an unusable one refused; a regular saver refusing the
  internal payment that passes its month's amount; a saver allowing one
  withdrawal a year refusing a second while the first is in flight, and
  allowing it once the first has failed; an account refusing the
  payment in that passes its product's most and the payment out that
  leaves less than its least.

## Alternatives Considered

- **A terms structure of the product's own.** Rejected: a second way to
  state a limit, checked by code the policy engine does not know.
- **Reading a limit from the interest bands.** Rejected: a band says
  what an amount earns, and an account may hold more than its top band.
- **Sums and counts on transaction legs.** Rejected: an outbound
  payment's settlement and its reversal put the same leg on the
  account, and every posting, interest among them, would pay for them.
- **An index for each period a limit names.** Rejected: one per month,
  quarter, year and anchor, where one keyed by day serves each by a
  range read.
- **Checking the amount paid in at the balance.** Rejected: it is a
  rule on payments, and interest paid into the account must pass it.

## Known Limitations

- **An inbound payment's limit has no scenario.** The check sits in
  `check-inbound-acceptance` with the path's others, and no API
  scenario sends a payment over a version's limit in from outside.

- **Every payment pays for the indexes.** A save adds to a sum and a
  count for each side whether or not the account's version carries a
  limit. Only the read is confined to the accounts that do.
- **A capped account's payments conflict.** The period is read
  serializably, so two payments into one such account at once retry.
- **A positive minimum cannot be emptied.** A balance minimum above
  zero refuses the payment out that would close the account, and
  nothing makes a customer pay in to reach it.
- **An overdraft a product allows is still refused.** The platform's
  limit keeps every account's available balance at zero or above, so a
  negative minimum takes effect only once that limit is removed.
- **A reward is held to the platform's limit alone.** Its posting
  passes no account's limits, so a house account's minimum above zero
  does not hold a reward back.
- **A year is read a day at a time.** An annual limit reads a group for
  each day and status the account was paid on.
- **A limit changes with the version.** A published version's limits
  are fixed: other limits are a new version, and its accounts move by
  migration.
- **A migration restarts an account's periods.** A period is counted
  from `version_from_on`, which a migration moves. A migration that
  changes a rate and keeps the limits would more often want the
  account's periods kept, so whether they restart belongs among the
  choices a migration states.

## References

- [prd/cash-account-products](../prd/cash-account-products.md) — what
  a customer states on a product and what its accounts then refuse.
- [cash-account-products](cash-account-products.md) — a version, its
  terms and when they are fixed.
- [policy-evaluation](policy-evaluation.md) — limits, bounds, windows
  and how a request is matched.
- [payments-internal](payments-internal.md) — the internal payment,
  which checks both its accounts.
- [payments-outbound](payments-outbound.md) — submission, settlement,
  failure and return, which decide whether a withdrawal counts.
- [payments-inbound](payments-inbound.md) — acceptance, and where a
  refused payment goes.
- [transactions-and-balances](transactions-and-balances.md) — the
  available balance and its limit.
- [schema-evolution](../recipes/code/schema-evolution.md) — adding an
  index and a field.
