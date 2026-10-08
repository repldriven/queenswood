# Plan: a version's terms — interest rates and rewards

A brief for the session that designs how a cash account product
version expresses its interest rate and its rewards. Nothing here is
decided. It says what exists, what has been asked, what binds the
answer, and what to settle first.

## What exists

- **The rate.** `CashAccountProduct.interest_rate_bps`, one required
  `int32` per version, in
  [cash-account-product.proto](/components/schema/resources/schemas/cash-account-products/cash-account-product.proto).
  Accrual reads it once per account per day in `interest/accrue.clj`
  and `interest/domain/accrual.clj`, actual/365, at sub-unit precision
  carried between days.
- **The reward promised.** `CashAccountProduct.opening_reward`, an
  optional `OpeningReward { amount }` in minor units of the version's
  one `currency`. The rewards TDD put it in a message so a later
  condition has a home.
- **The reward paid.** The `Reward` record in
  [reward.proto](/components/schema/resources/schemas/rewards/reward.proto),
  one per account and `RewardKind`, `DUE` or `PAID`, written by the
  reward processor as an account opens.
- **The interest runs.** `InterestRun` and `InterestAccountRun` in
  [interest-run.proto](/components/schema/resources/schemas/interest/interest-run.proto),
  one accrual or capitalisation per bank per day and per account.
- **The version.** One currency, terms fixed once published, an
  effective window, and accounts pinned to the version they opened on
  until a migration moves them.

## What has been asked

From the open questions in the [interest PRD](../prd/interest.md) and
the known limitations of the [rewards TDD](../tdd/rewards.md):

- **Tiered or stepped rates:** 5% on the first £10,000, 3% above.
- **A rate that changes within a version,** for a promotional period,
  rather than by publishing a new version.
- **A reward with a condition:** paid once a balance is reached, or
  after a period, or after a first deposit.
- **A second kind of reward,** and whether a version lists its rewards
  or carries a field per kind.
- **A bound on what a version may promise,** as a policy limit on the
  reward amount.

Per-currency rates within one version no longer arise: a version holds
one `currency`.

## What binds the answer

- The clean slate: nothing stored has to stay readable, so the shape
  can change freely until the next release.
- [record-protos](../recipes/code/record-protos.md): one record per
  file, the field bands, `required` meaning required, `_by` on every
  act.
- A published version's terms never change. Anything that varies over
  a version's life has to be stated in the terms when they are
  published, or the version is superseded.
- A migration moves accounts between versions, so terms are compared
  across versions when a migration is previewed.
- Interest stays integer arithmetic, non-negative, actual/365.

## What to settle first

1. Is a rate one number, or a schedule — tiers by balance, steps by
   date — and does accrual read it as a function of balance and day?
2. Does a version carry a field per reward kind, or a list of reward
   terms each with a kind and conditions?
3. What does a reward condition wait on: a balance, a date, an event on
   the account? Which of those does the processor already hear?
4. Do rates and rewards share a shape, as a version's terms, or stay
   separate?
5. What does the API take and return for each, and what does the
   console's product drawer show?

Read before starting: the [interest TDD](../tdd/interest.md), the
[rewards TDD](../tdd/rewards.md),
[cash-account-products](../tdd/cash-account-products.md),
[statementing](statementing.md) for how interest paid is reported, and
the [cash-account-products PRD](../prd/cash-account-products.md).
