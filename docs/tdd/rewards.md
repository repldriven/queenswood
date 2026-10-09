# Rewards

> **Status: proposal.** The bank's own-funds house account, the
> internal transfer that pays a customer from it, the product version
> as the immutable terms record, the scheduler and its jobs API, the
> simulate route that puts money into a bank, and the changelog relay
> that tells an endpoint exist, and Background names them. Everything
> under Proposed Solution is the build list; the term, the record, the
> entry, relay and kind that tell the bank, and the processor that pays
> a reward as an account opens are built, and "First slice" says what
> comes next.

## Objective

A bank offers a reward on a product — a welcome payment to every
customer who opens an account under it — and the platform pays it from
the bank's own funds without anybody submitting a transfer. This TDD
decides where the reward is declared, how a payment of it is recorded
exactly once, what pays it and when, how the bank is told, and how
money gets into a bank in the first place, since a reward is only ever
paid from money the bank already holds.

In scope: the reward term on a product version, the `Reward` record and
its changelog, the `reward` brick and the processor that pays a reward
as an account opens, the hourly periodicity and the two scheduler fixes
built for the job it replaced, the `reward` transaction type, the
`reward.paid` webhook kind, the read routes over rewards, and the
simulate route funding only the bank's own account.

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
- **The simulate route credits any account.**
  `POST /v1/simulate/inbound-transfer` takes an `account-id`,
  debits 1100 and credits whatever account is named, reading the
  account's product type only to check its control. Twelve API
  scenarios, the console's funding scene and the demo's fund recipe
  credit a customer account directly with it. The model-equality
  scenarios' `:inbound-transfer` verb is a different thing: it calls
  `payment/settle-inbound` with a synthetic scheme message.
- **The scheduler is per bank, registered at start.** A job is a
  `SchedulerJob` row seeded per bank at creation from
  [jobs.edn](/components/resources/resources/scheduler/jobs.edn), with
  a Quartz cron schedule in UTC, which each of its tasks limits. The
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

A version carries the rewards it promises as `reward_terms`, a list of
`RewardTerms` beside `interest_terms` in
[cash-account-product.proto](/components/schema/resources/schemas/cash-account-product/cash-account-product.proto),
each naming the `RewardKind` it is for, an enum the paid `Reward`
record shares from the folder's `types.proto`:

```proto
message RewardTerms {
  required RewardKind kind = 1;
  required int64 amount = 2; // minor units
}
repeated RewardTerms reward_terms = 16;
```

`REWARD_KIND_OPENING` is the one kind. Another, such as a referral,
adds a value and the event that pays it; a kind paid more than once to
an account adds what each payment is for to the `Reward` record and its
once-only index. The amount is in the version's one currency, so the
term carries none. Until the API takes the list, it takes
`opening-reward` and stores it as the opening kind's terms.
`product-fields` threads `:opening-reward` from the caller's data, which
covers create, new version and update in one edit, and a guard beside
`ensure-effective-window` refuses a non-positive amount with
`:cash-account-product/invalid-reward`. The template changes not at
all: like the rate, a reward is the bank's commercial choice per
version. Immutability and effective dating come for free — a customer
keeps the reward the version promised, and a January-only offer is a
published version with a one-month window.

### The record

An `AccountReward` is what was paid, or is owed, to one account, in a
new `account-rewards` store:

- `bank_id`, `reward_id` (`rwd.` prefix), `status` (`DEFERRED`,
  `PAID`), `kind` (`REWARD_KIND_OPENING`), `account_id`, `product_id`,
  `version_id`, `amount`, `currency`, `transaction_id`,
  `deferred_reason`, `deferred_at`, `paid_at`, `created_at` and
  `updated_at`.
- Primary key `[bank_id, reward_id]`; index
  `AccountReward_by_bank_account` on `[bank_id, account_id, kind]`,
  which is the once-only check.

`fdb-record-types.yml` bumps its `version` and declares the store with
`since` at that version, per
[schema-evolution](../recipes/code/schema-evolution.md). The row is
keyed by account rather than by version so that a migration onto a
version carrying a reward pays nothing: the account was rewarded for
its opening, once.

### The processor

A `reward` brick hosts a `:reward/event-processor` in the
financial-processors service, consuming `cash-accounts-event` under a
consumer group of its own beside the cash-account processor's and the
webhook's. Its `events.clj` acts on one entry,
`cash-account-status-changed` with the status after `opened` and the
change kind `open`, which is written in the transaction that opens the
account, so the account accepts a posting by the time the entry is
read. A migration or a resume leaves an opened account opened under
another kind, and a refusal is `open` with the status after `refused`;
none of them pays. `pay-opening` takes `{:bank-id :account-id}`:

1. Read the account, and keep it only where its status is `opened` and
   its product type a customer's.
2. Read its pinned version through `products/get-version`. A version
   with no `opening-reward`, or an account with an `AccountReward` row
   of kind `opening` already `paid`, pays nothing.
3. Pay the rest in the same FDB transaction as those reads: the
   `AccountReward` row `paid` with its changelog entry, and the transaction
   below, all committed together, so a crash leaves either nothing or a
   paid reward with its posting, and a redelivered entry finds the row
   paid and pays nothing. The house account comes from a cache keyed by
   bank and currency, since only a bank's creation changes it, and the
   control accounts' ids from the ledger cache the payment processor
   uses.
4. Where the posting is refused — the house account short of funds, or
   missing for the currency — write the row `deferred` with the
   refusal's message in `deferred_reason`, in a transaction of its own.
   Any other anomaly is answered, so the consumer delivers the entry
   again.

The financial-processors service hosts it because a reward is a
posting. It is its own brick, rather than a reaction inside
`cash-account`, because what earns a reward will grow: a later
condition is another event this processor listens to, not another
change to the account's processor.

### The transaction

A reward posts as `TRANSACTION_TYPE_REWARD`, a new value in
[transaction.proto](/components/schema/resources/schemas/transaction/transaction.proto),
with two posting legs — a debit on the house account for the version's
currency and a credit on the customer's, both `default` and `posted` —
which move 3100 and the customer's deposit control, each the sum of
its sub-ledger, once `ledger-accounts/ensure-controls` has checked
both are open. It is
recorded and applied with `transactions/record-and-post` inside the
account's transaction from step 3, under the idempotency key
`reward-<account-id>`, with the reference `Welcome reward`, which is
what the customer's statement line says. The type appears wherever
`internal-transfer` is listed in the policy seeds and the transaction
API's coercion, and `cash-account-query` gains `house-account`, the
bank's own-funds account for a currency, which the simulate route below
shares.

### Telling the bank

The `AccountReward` row's changelog entry, `account-reward-status-changed`,
carries the bank, the reward and account ids, the status before and
after and a change kind of `pay` or `defer`, in a new Avro schema under
`components/schema/resources/schemas/reward/`. A `reward-relay.yml`
declares the handler and one runner over the `account-rewards` store
onto a `rewards-event` channel, wired through the Kafka topics, the exclusive
dispatchers and external adapters services, the monoliths and the test
rigs exactly as `payments-event` was. The webhook brick catalogues
`reward.paid`, terminal on status `paid`, loading the record through
`reward-query/get-reward` and projecting it through `reward-api`, and
`resource-components` gains `Reward`. A `defer` is not published: it
is the bank's operational problem, read from the run and the row, and
not the customer's news.

### Funding the bank

`POST /v1/simulate/inbound-transfer` loses its
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
  it, the pattern the `funded-account` fixture uses; each step takes a
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

Three changes, made for the hourly reward job the processor replaced
and kept, since each holds for every job:

- **Hourly.** A job's schedule in
  [scheduler-job.proto](/components/schema/resources/schemas/scheduler/scheduler-job.proto)
  may fire hourly, `0 m * * * ?`; the jobs API's coercion and view
  order learn the word. No seeded task allows it.
- **Declared once, run locally.** A `system/scheduler.yml` declares the
  `scheduler` and `bank-scheduler` components, included by the monolith
  and exclusive-dispatchers manifests in place of their inline copies
  and by `monolith/application-test.yml`, so `just monolith-start`
  fires triggers.
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
- The product drawer gets a reward field beside the rate, and the
  products table a column, so an operator can see what a version
  promises.

### The demo

The seed sets an opening reward on the current-account version it
creates and funds the bank's house account. A customer who opens an
account is paid as it opens, and the demo bank hears `reward.paid` once
it consumes the kind. The money-arrived screen the demo's PRD wants is then the
reward's first customer.

### First slice

1. The hourly periodicity, the shared scheduler declaration and the
   sweep, with a cadence test and a tick test that starts mono's
   scheduler directly. This proves the scheduler fires, which nothing
   does today.
2. The term on the version and the `Reward` record, `product-fields`
   threading the term, the schema version bump, and the API's request
   and response shapes.
3. The `reward` brick and its processor in the financial-processors
   service, the transaction type and `house-account` in the query
   brick, proved by a scenario that publishes a rewarding version,
   opens an account, and polls its reward until it reads `paid`.

Then, under this design: the changelog, relay, `reward.paid`, the read
routes and the simulate route with the scenarios, the fund recipe and
the seed that move with it, all built; and the console. The demo bank
consuming `reward.paid` is [demo-digital-bank](demo-digital-bank.md)'s,
and is built.

### Tests

- `scheduler` — what each task's cadence allows a schedule in
  `domain_test.clj`; a tick test that starts mono's scheduler and sees
  a job fire; the sweep registering a bank created after start.
- `cash-account-product` — the term threads through create, new
  version and update, is refused non-positive, and is immutable once
  published.
- `reward` — domain tests for which entries are an opening (opened
  under `open`, never a refusal, a migration or a resume), eligibility
  (status, product type), what a version promises, the legs a reward
  posts, and the rows a paid and a deferred reward leave.
- `test-api-scenarios` — the opening-reward scenario above, and one
  opening an account on an unfunded bank whose reward reads `due` with
  why; the simulate route funding the house account and refusing
  nothing; the twelve rewritten funding steps still passing.
- `webhook` — `reward.paid` delivered once for a `pay` and never for a
  `defer`, in `events_test.clj`.

## Alternatives Considered

- **A scheduled job scanning every account.** Rejected: a reward
  waited for the hour, or for an operator to force the run, and every
  run read every account to find the few just opened. Taken in part:
  the once-only row is keyed by account, so that only an opening, never
  a migration, is paid.
- **A reaction inside the `cash-account` processor.** Rejected: it
  would pay sooner by a hop, but the account's processor would carry
  every condition a reward grows, and the posting would run in the
  operational group rather than beside the other postings.
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

- **A due reward waits.** A reward the house account could not cover
  is left `due`, visible on the read routes, and nothing pays it once
  the bank is funded. Paying it when own funds are credited needs an
  event for that posting, which the event per posting below would give
  the processor to listen to.
- **One kind, no condition.** Only an opening reward exists, paid once
  per account. A reward that waits for a balance or a period is the
  condition model this design leaves out.
- **Own funds are equity.** A reward reduces 3100 rather than posting
  to a rewards expense line, following the chart the platform has. A
  bank that wants rewards on its profit and loss needs a line the chart
  does not seed.
- **Nothing bounds the amount.** A policy tier limits how many products
  a bank may publish, not what a version may promise. A limit on the
  reward amount is a policy change this design does not make.
- **Only a reward and a transfer are heard.** The bank is told
  `reward.paid` and `payment.internal-settled`; a simulated funding and
  interest still post silently, and the event per posting stays
  deferred.
- **The house account must hold the currency.** A version in a currency
  the bank was not created with has no house account to pay from, and
  every reward under it is left `due`.

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
- [cash-accounts](cash-accounts.md) — the two-step opening whose
  `opened` entry the processor acts on.
- [processor-bricks](processor-bricks.md) — `events.clj` reacting to
  another brick's transition, the shape the processor takes.
- [scheduler](scheduler.md) — the jobs the hourly periodicity and the
  reconcile serve.
- [payments](payments.md) — the internal payment this design does not
  use, and the scheme inbound that funds a customer from outside.
- [webhooks](webhooks.md) — the catalogue `reward.paid` joins and the
  posting event that stays deferred.
- [scenario-testing](scenario-testing.md) — the model verb that shares
  a name with the simulate route.
- [ADR-0021](../adr/0021-changelog-relay.md) — why the reward's news
  travels off its changelog, and why the processor reacts to the
  account's rather than being called by it.
- [schema-evolution](../recipes/code/schema-evolution.md) — the version
  bump and `since` a new store needs.
