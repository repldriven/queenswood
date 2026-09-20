# Plan: the reward term and the Reward record

The second item of the first slice of [rewards](../tdd/rewards.md):
the `opening-reward` term on a product version, the `Reward` record
and store, and the API shapes that carry the term. Read the TDD's
"The term on the version", "The record" and "The API and the console"
before anything else; this plan says which files, in what order, and
how each step is proved. The first item, the scheduler, landed in
PR #669. The third, the `reward` brick and the hourly job that pays,
is the next plan, not this one.

Branch: `reward-term-and-record`, cut off `main` after #669. Worktree:
`adrock`. Read the memory notes before starting: the brick test-rig
classpath trap, the unregistered-component-kind trap, and the CI prep
race are all recorded.

## What this slice delivers

- A version may carry `opening-reward {amount}`, in minor units of the
  version's currency, immutable once published like the rate.
- A `Reward` record and a `rewards` store exist, keyed by account, with
  nothing writing to them yet.
- The products API accepts and returns the term, and the PRD names it.
- The migrator's schema evolution guard passes.

Nothing pays a reward in this slice. The house account, the transaction
type and the job are the third item's.

## Steps

### 1. The term, in the schema

- [ ] `components/schema/resources/schemas/cash-account-products/account-product.proto`:
      add a nested message and the field at tag 26, the next free one.
      Tags 9 and 20 to 22 are reserved and stay so.

      ```proto
      message OpeningReward {
        required int64 amount = 1;  // minor units of the version's currency
      }
      optional OpeningReward opening_reward = 26;
      ```

- [ ] `components/schema/src/com/repldriven/queenswood/schema/interface.clj`,
      `pb->CashAccountProduct` and `CashAccountProduct->java`: protojure
      hands an embedded message back as a record, which reitit cannot
      coerce, so `pb->` turns an `opening-reward` into a plain map, and
      an absent one stays absent. The scheduler store's `clean-task` is
      the pattern. A nested message needs none of the zero-default
      stripping a flat scalar would.
- [ ] `components/resources/resources/system/fdb-record-types.yml`:
      bump `version` from 54 to 55. A field added to an existing record
      needs the bump and no index work. Read
      [schema-evolution](../recipes/code/schema-evolution.md) first.
- [ ] `just force-prep`, then the schema brick's `interface_test.clj`
      gains a round trip of a version with the term and one without.

### 2. The term, in the brick

- [ ] `components/cash-account-product/src/.../domain.clj`:
      `product-fields` threads `:opening-reward` from the caller's data
      with `assoc-some`, which covers `new-product`, `new-version` and
      `update-version` in one edit. A guard beside
      `ensure-effective-window` refuses a non-positive amount with
      `:cash-account-product/invalid-reward`, called from the same
      three operations. `ensure-draft` already makes it immutable once
      published; nothing to add.
- [ ] `interface.clj`'s docstrings for create, new version and update:
      the create-data contract lives there and is the spec.
- [ ] `cash-account-product-query` needs no change; it returns whole
      version maps.
- [ ] Tests in `domain_test.clj` and `interface_test.clj`: the term
      threads through all three operations; a zero or negative amount
      is refused; a published version refuses a change to it; a version
      created without it reads back without it.

### 3. The record

- [ ] New `components/schema/resources/schemas/rewards/reward.proto`,
      `record.usage = RECORD`: `Reward` with `bank_id`, `reward_id`
      (`rwd.` prefix), `account_id`, `party_id`, `product_id`,
      `version_id`, `kind`, `amount`, `currency`, `status`,
      `transaction_id`, `run_id`, `error`, `created_at`, `updated_at`,
      `paid_at`; enums `RewardKind {UNKNOWN, OPENING}` and
      `RewardStatus {UNKNOWN, DUE, PAID}`. Optional fields carry the
      proto2 caveat the scheduler job proto documents: protojure drops
      a zero or false on the wire, so read sites strip them.
- [ ] `fdb-record-types.yml`: a `rewards` store, `record-type: Reward`,
      `since: 55`, primary key `[bank_id, reward_id]`, one index
      `Reward_by_bank_account` on `[bank_id, account_id, kind]` with
      `added: 55`, `modified: 55`, unique. The webhook-endpoints store
      is the shape to copy.
- [ ] `schema/interface.clj`: `pb->Reward` and `Reward->java`, with a
      round trip in the schema brick's test.
- [ ] `just force-prep` again, then `just test-all`'s evolution guard
      against the last `stable-*` tag.

### 4. The API

- [ ] `bases/api/src/com/repldriven/queenswood/api/cash_account_product/components.clj`:
      an `OpeningReward` component of one `amount`, reusing the minor
      units schema `api-schema` already declares rather than minting
      one; `[:opening-reward {:optional true} [:ref "OpeningReward"]]`
      on `CashAccountProductRequest`, `CashAccountProductDraftRequest`
      and `CashAccountProductVersion`; the component registered beside
      the others.
- [ ] `examples.clj`: the create example and the version example carry
      a reward, so the OpenAPI document shows one.
- [ ] No route and no coercion changes: the term has no enum.
- [ ] `components/test-api-scenarios/.../scenarios/cash-account-products/`:
      a scenario creating a product with a reward, reading the version
      back with it, publishing, and being refused a change to it; and
      `create-product-happy.edn` still passing without one. Idempotency
      keys are unique across every scenario file, and a key ending in
      six digits trips the cloud-identifier hook.

### 5. The PRD

- [ ] `docs/prd/cash-account-products.md`, "Creating a product": a
      sixth supplied field, in the product register — a welcome reward
      the bank pays a customer who opens an account under the version,
      as an amount in the product's currency, optional. No brick,
      relay or handler words. Run the docs checks.

## How it is proved

```bash
just force-prep
clojure -M:poly test brick:schema:cash-account-product:cash-account-product-query project:dev :all
clojure -M:poly test brick:api project:api-service :all
clojure -M:poly test brick:test-api-scenarios project:dev :all
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
- A brick test that starts a rig requires the brick's `interface`, or
  the kind is built as bare config; mono refuses this from v0.0.36.
- A CI job failing seconds into `deps prep` with a `HashMap` cast
  error is the prep race: rerun the job.
- Pull `main` before committing; commit and PR with `just` or `gh`
  against `main`; no attribution trailers; no realised cloud ids in a
  PR.
- Mono work goes on `release-v0.0.36` in the frank worktree, committed
  and not pushed; one commit is already there, the unregistered-kind
  refusal.

## Hand back

The PR title and body say what a tenant can now declare and what still
pays nothing. Update the memory note `rewards-programme` with the PR
number and anything the schema bump taught, and cut the next branch for
the third item: the `reward` brick, the `reward` transaction type,
`house-account` in the query brick, the task kind and the job in
`jobs.edn`, proved by the scenario the TDD's first slice names.
