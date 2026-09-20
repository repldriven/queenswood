# Plan: the reward brick and the hourly job

The third item of the first slice of [rewards](../tdd/rewards.md): the
`reward` brick that pays an opening reward, the `reward` transaction
type, `house-account` in the query brick, the task kind and the job in
`jobs.edn`, proved by the scenario the TDD's first slice names. Read
the TDD's "The job", "The transaction", "Alternatives Considered" and
"Known Limitations" before anything else; this plan says which files,
in what order, and how each step is proved. The first item, the
scheduler, landed in PR #669 and the second, the term on the version
and the `Reward` record, in PR #670. The changelog entry, the relay and
`reward.paid` are the next slice, not this one.

Branch: `reward-brick-and-job`, cut off `main` after #670. Worktree:
`adrock`. Read the memory notes before starting: the brick test-rig
classpath trap, the test-scenario tier, the CI prep race and the demo
bank's flaky stream test are all recorded.

## What this slice delivers

- A `reward` brick whose `pay-due` run pays every opened customer
  account whose pinned version promises an opening reward and which
  has no `Reward` row yet, one FDB transaction per account, and leaves
  a `due` row where the house account cannot pay.
- A `reward` transaction type, posted from the house account to the
  customer's under the key `reward-<account-id>` with the reference
  `Welcome reward`, mirrored onto 3100 and the deposit control.
- `house-account` in `cash-account-query`: the bank's own-funds account
  for a currency, found through two indexes rather than a scan.
- The `reward` task kind, allowed hourly and daily, and the
  `hourly-rewards` job every bank is seeded with.

Nothing tells the bank. The `Reward` row is written without a changelog
entry, and no route reads it: a paid reward is visible as the account's
balance and its `reward` transaction, and a deferred one as the run's
failed count.

## Steps

### 1. The type and the task kind, in the schema

- [ ] `components/schema/resources/schemas/transactions/transaction.proto`:
      `TRANSACTION_TYPE_REWARD = 7`.
- [ ] `components/schema/resources/schemas/scheduler/scheduler-job.proto`:
      `SCHEDULER_TASK_KIND_REWARD = 4`.
- [ ] `components/resources/resources/system/fdb-record-types.yml`:
      bump `version` from 55 to 56. An enum value added to a record
      type is a change to its descriptor, and the guard refuses a
      changed descriptor at the stored version.
- [ ] `just force-prep`. The label maps in `schema/interface.clj`
      (`transaction-type->int`, the `->pb-enum` helpers) pick the
      values up from the generated code.

### 2. The type, wherever `internal-transfer` is listed

- [ ] `components/transaction/src/.../domain.clj`: `type->status` maps
      `:transaction-type-reward` to posted. An unlisted type records as
      pending, which a reward is not.
- [ ] `components/resources/resources/policies/platform/restricted/limits/balances.yml`:
      a fourth filter, `transaction-type-reward`, so the available
      balance of the house account may not go negative on a reward.
      This is the refusal the job reads as "cannot pay".
- [ ] `bases/api/src/.../transaction/coercion.clj`: `"reward"`.

### 3. `house-account`

- [ ] `components/cash-account-product-query`: `find-products-by-type`
      over `CashAccountProduct_by_bank_product_type` with
      `fdb/query-records-compound`, the shape
      `find-version-by-idempotency-key` uses, returning the versions.
- [ ] `components/cash-account-query`: `house-account txn bank-id
      currency` — the bank's own-funds versions, the one whose
      `allowed-currencies` carries the currency, its accounts over
      `CashAccount_by_bank_product`, the one account. Rejects
      `:cash-account/house-account-not-found` where the bank has no
      own-funds account in the currency. The query brick gains the
      product query as a dependency; nothing points back.
- [ ] Interface docstrings on both. A test in the cash-account-query
      brick's rig: a bank with an own-funds product and account in GBP
      answers it, and asks for USD are refused.

### 4. The brick

New `components/reward/` with `deps.edn` (`test` and `test-resources`
in the test alias), `interface.clj`, `core.clj`, `domain.clj`,
`store.clj`, and a rig `test-resources/reward/application-test.yml`
including templates, fdb and policies. No `system.clj`: the scheduler
calls the run in process, as it calls interest's.

- [ ] `domain.clj`: `eligible?` (status opened, product type a
      customer's, not own-funds); `new-reward` (`rwd.` id, kind
      opening, the version's amount and the account's currency, status
      due); `paid` and `deferred`; `reward-transaction` — the map
      `record-and-post` takes, key `reward-<account-id>`, reference
      `Welcome reward`, a debit on the house account and a credit on
      the customer's, both `default` and `posted`, each carrying its
      `:product-type` so `add-control-legs` mirrors them.
- [ ] `store.clj`: `save-reward`, `find-by-account` over
      `Reward_by_bank_account`, `transact`. FDB confined here.
- [ ] `core.clj`: `pay-due` streams the bank's accounts with
      `reduce-accounts-with-balances`, memoising versions per run as
      the interest pass does and house accounts per currency. An
      account falls out when it is ineligible, its version carries no
      `opening-reward`, or its row is `paid`. The rest are paid one
      transaction each: the row is re-read inside the transaction, the
      legs expanded, `record-and-post` called, the row saved `paid`
      with the transaction id. A refusal aborts that transaction —
      nothing lands — and a second one writes the row `due` with the
      anomaly's message, counted failed. A `due` row is retried the
      same way on the next run. Answers `{:bank-id :as-of-date
      :accounts-processed :accounts-failed}`, processed being the
      accounts paid this run, so a run that pays nothing reports zero.
- [ ] `interface.clj`: `pay-due config {:bank-id :as-of-date}`, the
      docstring carrying the contract above.
- [ ] Tests. `domain_test.clj`: eligibility by status, by product
      type, by a version with no term; the legs and their sides; a
      paid row's transaction id; a deferred row's error.
      `interface_test.clj` in the rig: the chart seeded from
      `ledgers/general-ledger.edn`, an own-funds product and account
      opened with `seed-opened-account`, a customer account under a
      version with a reward; a run against an unfunded house leaves a
      `due` row and counts one failed; the house funded through
      `record-and-post` from 1100, the next run pays it, the row is
      `paid` with the transaction, the balances moved; a third run
      pays nothing; an account under a version with no term never gets
      a row.

### 5. The task and the job

- [ ] `components/scheduler/src/.../core.clj`: `:scheduler-task-kind-reward`
      in the registry, label `reward`, running `reward/pay-due`.
- [ ] `domain.clj`: `task-allowed-periods` allows the reward task
      hourly and daily; the comment saying nothing runs hourly goes.
- [ ] `components/resources/resources/scheduler/jobs.edn`:
      `hourly-rewards`, `Hourly rewards`, task `reward`, hourly at
      minute 0, enabled, kind user.
- [ ] `bases/api/src/.../jobs/coercion.clj`: `"reward"`.
- [ ] The scheduler's `core_test.clj` reconcile test expects the third
      trigger, `0 0 * * * ?`; `domain_test.clj` pins the reward task's
      periods. The jobs API's `list-happy.edn` lists the third job.
- [ ] `deps.edn` at the root and in every project that carries the
      scheduler — api, exclusive-dispatchers, monolith,
      operational-processors — gain `components/reward`; the root's
      dev test paths gain its `test` and `test-resources`. `poly check`
      names any project left out.

### 6. The scenarios

- [ ] `scenarios/rewards/opening-reward-paid.edn`: a bank on the
      `test-scenario` tier, its house account funded through simulate,
      a product with a reward published, a party made active, an
      account opened and polled to `opened`; `POST
      /v1/jobs/hourly-rewards/runs` succeeds with the `reward` task
      processing one and failing none; the account's balance carries
      the reward and its transactions a `reward` referenced `Welcome
      reward`; a second forced run processes none.
- [ ] `scenarios/rewards/opening-reward-deferred.edn`: the same with
      the house account unfunded, so the first run fails one and the
      balance stays at zero; funded, the next run pays it.
- [ ] `full-happy-path.edn` still passes: its rewards are internal
      transfers under versions with no term.

### 7. The docs

- [ ] This plan is the only doc that moves. The TDD stays a proposal
      and already describes the job; the PRD names no job.

## How it is proved

```bash
just force-prep
clojure -M:poly test brick:schema:transaction:cash-account-query:cash-account-product-query:reward:scheduler project:dev :all
clojure -M:poly test brick:reward:scheduler project:exclusive-dispatchers-service :all
clojure -M:poly test brick:api project:api-service :all
clojure -M:poly test brick:test-api-scenarios project:dev :all
clojure -M:poly test brick:migrator project:migrator-service :all
clojure -M:poly check
bash .tessl/plugins/mono/docs/skills/check-docs/checks.sh
```

Run a brick's tests under a service project that carries it as well as
under `dev` before pushing: the root `deps.edn` serves `dev` alone, and
CI runs each service project on its own classpath.

## Rules that bit last time

- Format with `clojure -M:format/zprint '{:search-config? true}' -w`,
  never bare zprint; the hook formats, lints and scans on commit, and
  `--no-verify` is never the answer.
- A brick test that starts a rig requires every brick's `interface`
  whose kind the rig declares, or the kind is built as bare config.
- A scenario that meets a micro-tier cap on the way to its point puts
  its bank on the `test-scenario` tier.
- A CI job failing seconds into `deps prep` with a `HashMap` cast
  error is the prep race; the demo bank's notifications test failing
  on its ten-second stream wait is a flake. Rerun either.
- Pull `main` before committing; commit and PR with `just` or `gh`
  against `main`; no attribution trailers; no realised cloud ids in a
  PR.

## Hand back

The PR title and body say what a bank's customers are now paid and
what the bank is still not told. Update the memory note
`rewards-programme` with the PR number and cut the next branch for the
next slice: the `reward-status-changed` changelog entry, the relay onto
`rewards-event`, the `reward.paid` webhook kind, and the read routes.
