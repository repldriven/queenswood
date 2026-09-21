# Rewards

> **Status: proposal.** The bank's own-funds house account, the
> internal transfer that pays a customer from it, the product version
> as the immutable terms record, the scheduler and its jobs API, the
> simulate route that puts money into a bank, and the changelog relay
> that tells an endpoint exist, and Background names them. Everything
> under Proposed Solution is the build list; the term, the record, the
> hourly job that pays it and the entry, relay and kind that tell the
> bank are built, and "First slice" says what comes next.

## Objective

A bank offers a reward on a product — a welcome payment to every
customer who opens an account under it — and the platform pays it from
the bank's own funds without anybody submitting a transfer. This TDD
decides where the reward is declared, how a payment of it is recorded
exactly once, which job pays it and on what cadence, how the bank is
told, and how money gets into a bank in the first place, since a reward
is only ever paid from money the bank already holds.

In scope: the reward term on a product version, the `Reward` record and
its changelog, the `reward` brick and the hourly job that runs it, the
hourly periodicity and the two scheduler fixes the job depends on, the
`reward` transaction type, the `reward.paid` webhook kind, the read
routes over rewards, and the simulate route funding only the bank's own
account.

Out of scope: a reward with a qualifying condition (a balance held for
a period, a referral), which needs a condition model this design leaves
room for and does not build; interest, decided in
[interest](interest.md); money reaching a customer from another bank,
which is the scheme simulator's inbound payment, and an internal
transfer telling the account it credits, `payment.internal-settled`,
both decided in [payments](payments.md); an event for every posting on
an account,
which is deferred and noted in [webhooks](webhooks.md); and the demo
bank consuming `reward.paid`, which is the demo's own slice in
[demo-digital-bank](demo-digital-bank.md).

## Background

- **The version is the terms record.** A product is the set of
  `CashAccountProduct` rows sharing a `product_id`, and the version is
  the unit of storage. Its caller-chosen terms are the name, the
  currency, `interest_rate_bps` and the effective window; `product-fields`
  in the `cash-account-product` brick's `domain.clj` is the one function
  that assembles them, and `ensure-draft` refuses a change to anything
  published. The template carries the instrument's shape and no rate. An
  account pins its `product-id` and `version-id` at opening, and only a
  migration re-points them. See
  [cash-account-products](cash-account-products.md).
- **The bank's own funds exist and are paid out of.** Bank creation
  opens an own-funds house cash account per currency on the bank's
  organisation party, under an internal product built from
  [own-funds.yml](/components/resources/resources/cash-account-product-templates/own-funds.yml),
  rolling up to the 3100 equity control. Money in is a debit on 1100
  and a credit on the house account, and a reward is an internal
  transfer from it to the customer. The e2e API scenario does exactly
  this by hand. See [chart-of-accounts](chart-of-accounts.md) and
  [banks](banks.md).
- **The simulate route credits any account.** `POST
  /v1/simulate/banks/{bank-id}/inbound-transfer` takes an `account-id`,
  debits 1100 and credits whatever account is named, reading the
  account's product type only to pick the control leg. Twelve API
  scenarios, the console's funding scene and the demo's fund recipe
  credit a customer account directly with it. The model-equality
  scenarios' `:inbound-transfer` verb is a different thing: it calls
  `payment/settle-inbound` with a synthetic scheme message.
- **The scheduler is per bank, registered at start.** A job is a
  `SchedulerJob` row seeded per bank at creation from
  [jobs.edn](/components/resources/resources/scheduler/jobs.edn), with
  a periodicity of daily, monthly or yearly and a time of day, turned
  into a Quartz cron by `domain/->cron` in the `scheduler` brick. The
  `bank-scheduler/runner` registers one trigger per enabled job across
  every bank when it starts, and the run calls the task's brick in
  process, synchronously, recording a `SchedulerRun` with per-task
  counts. `POST /v1/jobs/{job-id}/runs` forces a run in the API's own
  thread. The `InterestAccountRun` row, written in the same transaction
  as the posting it marks, is the pattern for a pass that touches every
  account once. See [interest](interest.md).
- **Two things about the scheduler are true and undocumented.** No
  local or test configuration declares the `scheduler` and
  `bank-scheduler` components — the two service manifests declare them
  inline and `monolith/application-test.yml` does not — so
  `just monolith-start` fires no trigger and every test runs a job by
  forcing it. And a bank created after the runner started has job rows
  and no trigger until the service restarts, as does every existing
  bank when a job is added to `jobs.edn`, since seeding happens only at
  bank creation and registration only at start.
- **A processor tells the world through its changelog.** A brick writes
  a changelog entry in the transaction that changes its record, a relay
  runner puts the store's entries on a channel, and the webhook brick
  catalogues the kinds an endpoint may subscribe to, loading the record
  back and projecting it through its `-api` brick. The payment brick's
  status changes are the latest example. See
  [webhooks](webhooks.md) and [ADR-0021](../adr/0021-changelog-relay.md).

## Proposed Solution

### The term on the version

A version carries an optional `opening_reward`, a nested message beside
`interest_rate_bps` in
[account-product.proto](/components/schema/resources/schemas/cash-account-products/account-product.proto),
at the next free tag:

```proto
message OpeningReward {
  required int64 amount = 1;  // minor units of the version's currency
}
optional OpeningReward opening_reward = 26;
```

The amount is in the version's one currency, so the term carries none.
`product-fields` threads `:opening-reward` from the caller's data, which
covers create, new version and update in one edit, and a guard beside
`ensure-effective-window` refuses a non-positive amount with
`:cash-account-product/invalid-reward`. The template changes not at
all: like the rate, a reward is the bank's commercial choice per
version. Immutability and effective dating come for free — a customer
keeps the reward the version promised, and a January-only offer is a
published version with a one-month window.

### The record

A `Reward` is what was paid, or is due, to one account, in a new
`rewards` store:

- `bank_id`, `reward_id` (`rwd.` prefix), `account_id`, `party_id`,
  `product_id`, `version_id`, `kind` (`REWARD_KIND_OPENING`),
  `amount`, `currency`, `status` (`DUE`, `PAID`), `transaction_id`,
  `run_id`, `error`, `created_at`, `updated_at`, `paid_at`.
- Primary key `[bank_id, reward_id]`; index `Reward_by_bank_account` on
  `[bank_id, account_id, kind]`, which is the once-only check.

`fdb-record-types.yml` bumps its `version` and declares the store with
`since` at that version, per
[schema-evolution](../recipes/code/schema-evolution.md). The row is
keyed by account rather than by version so that a migration onto a
version carrying a reward pays nothing: the account was rewarded for
its opening, once.

### The job

A `reward` brick with a `pay-due` run function, called by the scheduler
the way `interest/accrue-day` is, taking the runner's config and
`{:bank-id :as-of}` and answering `{:accounts-processed
:accounts-failed}`:

1. Stream the bank's accounts with
   `cash-account-query/reduce-accounts-with-balances`, keeping those
   whose status is `opened` and whose product type is a customer's.
2. For each, read the pinned version through `products/get-version`,
   memoised per run as the interest pass does. An account whose version
   carries no `opening-reward`, or which already has a `Reward` row of
   kind `opening`, falls out.
3. Pay the rest, one FDB transaction per account: the `Reward` row
   `paid` with its changelog entry, and the transaction below, all
   committed together, so a crash leaves either nothing or a paid
   reward with its posting.
4. Where the posting is refused — the house account short of funds is
   the case that matters — write the row `due` with the anomaly's
   message in `error`, count it failed, and carry on. The next run
   finds the row `due` and tries again, so funding the bank is the
   remedy and nothing has to be resubmitted.

The job is `hourly-rewards` in `jobs.edn`, task kind
`SCHEDULER_TASK_KIND_REWARD`, periodicity hourly at minute 0, kind
`user`, so a bank may pause it or move it to daily.

### The transaction

A reward posts as `TRANSACTION_TYPE_REWARD`, a new value in
[transaction.proto](/components/schema/resources/schemas/transactions/transaction.proto),
with two posting legs — a debit on the house account for the version's
currency and a credit on the customer's, both `default` and `posted` —
and the control legs `ledger-accounts/add-control-legs` appends, which
mirror them onto 3100 and the customer's deposit control. It is
recorded and applied with `transactions/record-and-post` inside the
account's transaction from step 3, under the idempotency key
`reward-<account-id>`, with the reference `Welcome reward`, which is
what the customer's statement line says. The type appears wherever
`internal-transfer` is listed in the policy seeds and the transaction
API's coercion, and `cash-account-query` gains `house-account`, the
bank's own-funds account for a currency, which the simulate route below
shares.

### Telling the bank

The `Reward` row's changelog entry, `reward-status-changed`, carries the
bank, the reward and account ids, the status before and after and a
change kind of `pay` or `defer`, in a new Avro schema under
`components/schema/resources/schemas/rewards/`. A `reward-relay.yml`
declares the handler and one runner over the `rewards` store onto a
`rewards-event` channel, wired through the Kafka topics, the exclusive
dispatchers and external adapters services, the monoliths and the test
rigs exactly as `payments-event` was. The webhook brick catalogues
`reward.paid`, terminal on status `paid`, loading the record through
`reward-query/get-reward` and projecting it through `reward-api`, and
`resource-components` gains `Reward`. A `defer` is not published: it
is the bank's operational problem, read from the run and the row, and
not the customer's news.

### Funding the bank

`POST /v1/simulate/banks/{bank-id}/inbound-transfer` loses its
`account-id`. Its body is `{amount currency}`, it credits the house
account for that currency, and its response names the account it
credited. A bank funds itself, so there is nothing to choose, and the
field was only a way to be wrong. A currency the bank was not created
with answers `:gl/missing-currency-account` 409 as it does today. The
route's security stays `org:developer` and `admin`, and the tenant
guard in the handler stays, since a bank may still fund only itself.

What moves with it:

- The twelve API scenarios that fund a customer directly become two
  steps, the house account funded and `POST /v1/payments/internal` from
  it, the pattern `full-happy-path.edn` already uses; each step takes a
  fresh idempotency key. The scenarios that deliberately drive money at
  a closed or unmatched account are read individually.
- The console's funding scene funds the bank once and transfers twice,
  and its story, labels and backing list say so.
- `just demo-digital-bank-fund` drops its account argument and funds
  the bank; a customer is paid by the reward, or by the transfer the app
  makes, and money from another bank is the scheme simulator's.
- The schemathesis fixture in `test.just` that posts to a path under
  `organizations`, which no longer exists, is removed or repointed.
- [authentication](authentication.md) and [idempotency](idempotency.md)
  say what the route now does, and
  [chart-of-accounts](chart-of-accounts.md) notes that a customer funded
  from another bank is the scheme's path rather than the simulator's.
  [scenario-testing](scenario-testing.md) says the model's
  `:inbound-transfer` is a scheme inbound and shares nothing but a name
  with the route.

### The scheduler

Three changes, each small and each needed before the job can run
anywhere but a forced run:

- **Hourly.** `SCHEDULER_PERIODICITY_HOURLY` in
  [scheduler-job.proto](/components/schema/resources/schemas/scheduler/scheduler-job.proto);
  `->cron` answers `0 m * * * ?` for it, where `run-time-minutes` is
  the minute past the hour and the schedule guard refuses sixty or
  more; `task-allowed-periods` allows the reward task hourly and daily;
  the jobs API's coercion and view order learn the word.
- **Declared once, run locally.** A `system/scheduler.yml` declares the
  `scheduler` and `bank-scheduler` components, included by the monolith
  and exclusive-dispatchers manifests in place of their inline copies
  and by `monolith/application-test.yml`, so `just monolith-start`
  fires triggers and the demo pays its rewards on the hour.
- **A reconcile.** The runner makes the live triggers match the job
  rows at start and, on a trigger of its own, every minute after: a
  template a bank has no row for is seeded, and only that one, since
  `seed-jobs` overwrites what an operator edited; an enabled job with
  no trigger, or whose cron changed, is registered on a closure that
  reads its row again at fire time; a disabled one is removed. The
  runner remembers what it registered, keyed by trigger id, so an
  unchanged row costs nothing and mono needs no way to ask. A bank
  created after the runner started, a job added to `jobs.edn` after
  the bank, and an edit made through the jobs API, which runs in
  another JVM and reaches no trigger today, all take effect within the
  minute.

### The API and the console

- `opening-reward` on `CashAccountProductRequest`,
  `CashAccountProductDraftRequest` and `CashAccountProductVersion`, an
  `OpeningReward` component of one `amount`, with the examples.
- `GET /v1/rewards`, filtered by `account-id`, and
  `GET /v1/rewards/{reward-id}`, `org:viewer`, from `reward-query`;
  `reward-api` holds the shapes, so the webhook projects with the
  schema the routes declare.
- The jobs API needs nothing: `hourly-rewards` lists, forces and
  reschedules like the others.
- The product drawer gets a reward field beside the rate, and the
  products table a column, so an operator can see what a version
  promises.

### The demo

The seed sets an opening reward on the current-account version it
creates and funds the bank's house account. A customer who opens an
account is paid on the hour, or when an operator forces the job from
the console, and the demo bank hears `reward.paid` once it consumes the
kind. The money-arrived screen the demo's PRD wants is then the
reward's first customer.

### First slice

1. The hourly periodicity, the shared scheduler declaration and the
   sweep, with a cadence test and a tick test that starts mono's
   scheduler directly. This proves the scheduler fires, which nothing
   does today.
2. The term on the version and the `Reward` record, `product-fields`
   threading the term, the schema version bump, and the API's request
   and response shapes.
3. The `reward` brick, the transaction type, `house-account` in the
   query brick, the task kind and the job in `jobs.edn`, proved by a
   scenario that publishes a rewarding version, opens an account,
   forces the job, polls the balance, and forces it again to pay
   nothing.

Then, under this design: the changelog, relay, `reward.paid` and the
read routes, which are built; the console; and last the simulate route
with the scenarios, the console scene, the fund recipe and the docs
that move with it. The demo bank consuming `reward.paid` is
[demo-digital-bank](demo-digital-bank.md)'s, and is built.

### Tests

- `scheduler` — `->cron` for hourly and the sixty-minute guard in
  `domain_test.clj`; a tick test that starts mono's scheduler and sees
  a job fire; the sweep registering a bank created after start.
- `cash-account-product` — the term threads through create, new
  version and update, is refused non-positive, and is immutable once
  published.
- `reward` — domain tests for eligibility (status, product type, a
  version with no reward, a row already there), the legs a reward
  posts, and a run that leaves a `due` row when the house account
  cannot pay and pays it on the next run.
- `test-api-scenarios` — the opening-reward scenario above; the
  simulate route funding the house account and refusing nothing; the
  twelve rewritten funding steps still passing.
- `webhook` — `reward.paid` delivered once for a `pay` and never for a
  `defer`, in `events_test.clj`.

## Alternatives Considered

- **A processor on `cash-account.opened`.** Rejected: the moment a
  reward is paid is the scheme's rule rather than the write's, and a
  job is where a later qualifying condition is evaluated, where a
  bank's transfers batch, and what an operator pauses and forces
  through the jobs API. Taken in part: the job's once-only row is keyed
  by account so that only an opening, never a migration, is paid.
- **A flat `opening_reward_amount` beside the rate.** Rejected: a
  nested message gives a later condition a home and needs no stripping
  of the proto2 zero default that a flat scalar would.
- **A reward scheme record keyed by product version.** Rejected: it
  would be the first thing pointing at a version from outside, and it
  would be editable after publish, which the version's immutability
  exists to prevent.
- **Paying through the payment brick's internal payment.** Rejected: it
  posts `internal-transfer`, which is a customer's act and reads as one
  on a statement, it commits in a transaction of its own so the row and
  the posting could not be one commit, and the per-bank daily limit on
  internal payments would cap a bank's rewards.
- **Keeping `account-id` on the simulate route behind a guard.**
  Rejected: every call would still name the one account there is, and
  the guard would exist to refuse a field nobody needs.

## Known Limitations

- **The scan is every account, every hour.** The first slice reads the
  bank's accounts each run and asks for a row per opened one. An index
  on `[bank_id, created_at]` bounding the scan to accounts opened since
  the previous run is the follow-up when a bank's size demands it.
- **One kind, no condition.** Only an opening reward exists, paid once
  per account. A reward that waits for a balance or a period is the
  condition model this design leaves out.
- **Own funds are equity.** A reward reduces 3100 rather than posting
  to a rewards expense line, following the chart the platform has. A
  bank that wants rewards on its profit and loss needs a line the chart
  does not seed.
- **A forced run runs in the API's pod.** It can overlap the hour's
  tick for the same bank; the once-only row makes the overlap pay
  nothing twice, but both runs read the same accounts.
- **Nothing bounds the amount.** A policy tier limits how many products
  a bank may publish, not what a version may promise. A limit on the
  reward amount is a policy change this design does not make.
- **Only a reward and a transfer are heard.** The bank is told
  `reward.paid` and `payment.internal-settled`; a simulated funding and
  interest still post silently, and the event per posting stays
  deferred.
- **The house account must hold the currency.** A version in a currency
  the bank was not created with has no house account to pay from, and
  every reward under it defers.

## References

- [cash-account-products](../prd/cash-account-products.md) — the
  product this design adds a term to, and the register its API
  language keeps.
- [demo-digital-bank](../prd/demo-digital-bank.md) — the money-arrived
  journey the reward is the first customer of.
- [cash-account-products](cash-account-products.md) — the version as
  the terms record, `product-fields`, immutability and effective
  dating.
- [chart-of-accounts](chart-of-accounts.md) — the house account, the
  3100 control and the two journals a reward is made of.
- [banks](banks.md) — bank creation seeding the chart, the house
  accounts and the jobs.
- [interest](interest.md) — the run pattern, the per-account row and
  the scheduler's caller contract this job copies.
- [payments](payments.md) — the internal payment this design does not
  use, and the scheme inbound that funds a customer from outside.
- [webhooks](webhooks.md) — the catalogue `reward.paid` joins and the
  posting event that stays deferred.
- [scenario-testing](scenario-testing.md) — the model verb that shares
  a name with the simulate route.
- [ADR-0021](../adr/0021-changelog-relay.md) — why the reward's news
  travels off its changelog.
- [schema-evolution](../recipes/code/schema-evolution.md) — the version
  bump and `since` a new store needs.
