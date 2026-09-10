# Cash account products: gap analysis

Subject: [cash-account-products.md](../cash-account-products.md) —
Cash account products.

## Verdict

**Fail.**

The lifecycle the TDD is built on holds: a version is a draft until
it is published or discarded, `ensure-draft` refuses every write to
anything else with `:cash-account-product/version-immutable`, one
draft per product is enforced by `draft-already-exists`, a version
pins one currency, and account opening resolves `active-version` for
today off the published versions' `[effective-from, effective-to)`
windows and stores both ids on the account. The verdict fails on
what has grown around that core:

- Accounts are moved between versions by a migration subsystem with
  its own bricks, API, scheduler task and policy limits, while the
  TDD says the pin is immutable and only new accounts see a new
  version (F1).
- A product is created from a platform template held in FDB, which
  fixes its product type and every instrument field and gates its
  currency; the TDD has the caller naming the product type and a
  classpath file filling the rest in (F2).
- The brick is two bricks, and the file list names one file that does
  not exist and one that nothing calls (F3).
- The create path checks the count cap in a different transaction
  from the one that writes (F4), and the idempotency key is stamped
  by an operation that should not stamp it and dropped by one that
  should keep it (F5).
- The PRD's goals and non-goals contradict both the TDD and the code
  (F8).
- Nothing exercises the write brick against FDB (Missing evidence).

The verdict flips to pass when the TDD describes the template model,
the query split and the migration path, the create path is made
atomic or the TDD says it is not, the key lifecycle is fixed in
`update-version`, the PRD is reconciled, and a test runs the brick
against a record store.

## What was examined

- The subject TDD, and the sibling TDDs it cites: cash-accounts,
  interest, policy-evaluation and transactions-and-balances, plus
  the idempotency TDD for the key read-back it describes.
- The `cash-account-product` brick: interface, core, domain, store,
  validation and system files, its two test namespaces and its
  test-resources system file.
- The `cash-account-product-query` brick: interface, core, domain and
  store, and its domain test.
- The `api` base: the product routes, handlers, queries, components,
  coercion and examples, and the error-status table.
- The `CashAccountProduct` and `CashAccountProductTemplate` protos,
  the record-type metadata for the `cash-account-products` and
  `cash-account-product-templates` stores, the four template seeds
  under `resources`, their system file, and the platform and micro
  policy seeds for products.
- The consumers of the version: `cash-account` (open, rotate-address
  and migrate), `interest` (accrue), `bank` (the house product), and
  the `cash-account-migration` brick's domain and its scheduler
  hook.
- The API scenario suite's `cash-account-products` and
  `cash-account-migrations` folders, the domain scenario runner's
  product verbs, the property model's product commands, and the
  product projection.
- The cash-account-products PRD, the cash-account-migration plan,
  and ADR-0017 and ADR-0018.

Nothing was executed. Every finding below was traced in source, and
the ones that predict runtime behaviour say so.

## What matches

- Versions are stored under `[bank-id, product-id, version-id]`, the
  product has no record of its own, and `get-product` returns the
  versions newest-first.
- `new-product` mints a `prd.` id and a `prv.` id and starts at
  version 1 in draft; `open-draft` numbers the next version
  `(inc (count versions))`, so a discarded draft keeps its number
  and the next draft takes a fresh one.
- `ensure-draft` guards `update-version`, `publish` and `discard`,
  rejecting `:cash-account-product/version-immutable` for a published
  or discarded version, and the domain test covers all six cases.
- `new-version` rejects `:cash-account-product/draft-already-exists`
  when any version of the product is a draft, and the API scenario
  proves the 409.
- A version's `:allowed-currencies` is the caller's single currency
  wrapped in a vector, and the account-opening code checks the
  account's currency against it.
- `discard` stamps `:discarded-at` and leaves the row in place.
- The mutating operations resolve policies with
  `get-effective-policies` keyed by `{:bank-id}` for `new-product`
  and `{:bank-id :cash-product-id}` for the version-scoped ones, and
  `:policies` in `opts` skips the lookup; the reads are not gated.
- The capability request is `{:action … :product-type …}` with
  `:cash-account-product-action-draft` on create, open and update and
  `:cash-account-product-action-publish` on publish, so a
  product-type filter on a policy works.
- `active-version` picks, of the published versions whose window
  contains the day, the greatest `effective-from` then
  `version-number`, and returns nil when none applies; the query
  brick's pure test pins the overlap, gap and draft cases.
- `ensure-effective-window` requires `effective-from` and rejects an
  `effective-to` not strictly after it.
- `cash-account/open-account` resolves `active-version` for
  `utility/today`, refuses when it is nil, and stores `:product-id`
  and `:version-id` on the account; the bank's own-funds house
  product is created, published and opened the same way.
- Every write is a synchronous interface call from the API handler,
  as the TDD says; ADR-0018 records why.

## Findings

Severity is High where a stated guarantee is broken, Medium where the
design and the code disagree in a way a reader would act on, Low where
the document is incomplete.

### F1. High — accounts move between versions, and the TDD says they cannot

The TDD says the account "keeps reading its own `:version-id`; only
newly-opened accounts see the new version", that "new versions only
apply to new accounts", and that "the pinning is immutable as long as
the version itself is". The cash-account brick's `migrate-product`
repins an opened account to another version, and to another product
when the target belongs to one. Around it sits a `cash-account-migration`
brick that authors a migration naming a source product, a published
target version of the same product type, and a notification and an
effective date; previews it per account with per-account verdicts;
and commits it from the scheduler's account-migration task. The API
exposes it under `/v1/cash-account-migrations` with author, approve,
cancel and preview operations, three record types carry its state,
the platform and micro policies carry limits for it, and the capability
is `:cash-account-action-migrate`. The commit scenario shows an
account that read back on v1 reading back on v2 after the task runs.
No TDD describes any of this: the design is in
[cash-account-migration.md](../cash-account-migration.md)
under `docs/plan`, and the PRD lists repricing existing accounts as a
non-goal. Evidence: `migrate-product` in the cash-account brick's
`domain.clj`, `ensure-target-published` and `ensure-same-product-type`
in the migration brick's `domain.clj`, and the `commit` scenario under
`cash-account-migrations`.

Fix: restate the cohort property as "pinned at open, and moved only
by an approved migration between published versions of one product
type", add a section that names the migration path and links its
design, and fix the cash-accounts TDD's "immutable per ADR" on the
account record. Promote the plan to a TDD or fold it into this one.

### F2. High — a product is created from a template record, not a product type

The TDD's data model has the caller supplying `:name`, `:currency`,
`:product-type`, the window and optionally `:interest-rate-bps` and
`:iso-cash-account-type`, with "the rest of the instrument terms
filled from a per-product-type template (see `resources.clj`)" that
is "loaded once from `resources` on the classpath". The request
schema requires a `:template-id` and has no product-type field.
`product-fields` snapshots product type, balance-sheet side, balance
buckets, payment-address schemes, ISO type and `:internal` from a
`CashAccountProductTemplate` record in the
`cash-account-product-templates` store, and stamps `:template-id`
for provenance. The templates are seeded at bootstrap by the write
brick's `system.clj`, one component per YAML file under
`resources/cash-account-product-templates` — current, savings,
term-deposit and the internal own-funds — under stable `tpl.` ids so
re-seeding is a no-op; `new-template` is on the write interface and
`get-template` and `list-templates` on the query one, with internal
templates hidden. The template's `allowed-currencies` gates the
caller's currency with `:cash-account-product/currency-not-allowed`,
which the routes document as a 422. The three customer templates
allow GBP only, so a bank created with EUR or USD gets house accounts
in those currencies and can offer no customer product in them.
Traced, not executed. The record shape omits `:template-id`,
`:internal`, `:discarded-at` and `:idempotency-key`, and the
limitation that templates are "static and global … intended to move
to per-bank FDB records later" is half true: they are FDB records
now, still platform-scoped, still changed only by a redeploy.
Evidence: `CashAccountProductRequest` in the api base's product
`components.clj`, `product-fields` in the write brick's
`domain.clj`,
[savings.yml](/components/resources/resources/cash-account-product-templates/savings.yml)
and the `CashAccountProductTemplate` message in
[account-product.proto](/components/schema/resources/schemas/cash-account-products/account-product.proto).

Fix: rewrite the data model around the template — what the caller
supplies, what is snapshotted, what is stamped — list the currency
rejection, say what the customer templates allow and what that means
for a multi-currency bank, and restate the limitation.

### F3. Medium — the Architecture lists dead files and misses a brick

The TDD names `domain.clj`, `store.clj`, `validation.clj` ("Malli
schema validation"), `resources.clj`, `core.clj` and
`interface.clj`. There is no `resources.clj`. `validation.clj` holds
`unique-fields?`, a duplicate-items check that nothing calls but its
own test, and the api base's error table maps
`:cash-account-product/duplicate-items` to 422 for a kind nothing
produces; the Malli schemas are the API's. `system.clj`, which seeds
the templates, is not listed. Every read — `get-version`,
`get-product`, `get-products`, `get-template`, `list-templates`,
`active-version`, the two counts and the idempotency lookup — lives
in `cash-account-product-query` under ADR-0017, which the TDD does
not name; the write brick requires it inside its own transactions and
the api base requires only it for reads. The code's comments have
not caught up with ADR-0018 either: `core.clj`, `domain.clj` and the
proto still describe "a redelivered create-cash-account-product
command", and the query interface and both `store.clj` files name a
`bank-cash-account-product` brick that does not exist. Evidence:
`validation.clj` in the write brick, `rejection-status-overrides` in
the api base's `errors.clj`, and the query brick's `interface.clj`.

Fix: list both bricks and their files, cite ADR-0017 for the split
and ADR-0018 for why the writes are synchronous, delete
`validation.clj`, its test and the 422 override, and fix the stale
comments.

### F4. Medium — the count cap is checked outside the transaction that writes

`new-product` is the one lifecycle operation not wrapped in
`store/transact`. The handler passes a config map, and given a config
`fdb/transact` opens a fresh transaction for each call, so the policy
lookup, the template read, the two counts and `save-version` each
commit on their own. Two creates racing at the cap both count the
same `existing`, both pass `check-limit`, and both save. Traced, not
executed. `open-draft`, `update-draft`, `discard-draft` and `publish`
are wrapped, so the single-draft check and its write are one
transaction and a conflict retries into the rejection. The
unwrapping looks deliberate — `or-already-created` needs the unique
index violation to surface from `save-version`'s commit — but it
costs the cap its atomicity, and the TDD's "the lifecycle is short
enough not to need eventual consistency" does not warn a reader.
Evidence: `new-product` in the write brick's `core.clj` and `transact`
in the fdb brick's `transact.clj`.

Fix: run the read, the check and the write in one `store/transact`
and apply the read-back to that transaction's result, then add a test
that races two creates at the cap. Say in the TDD which operations
are atomic with their guards.

### F5. Medium — open-draft stamps the idempotency key, update-draft drops it

The proto and `new-version`'s comment say only `new-product` stamps
`idempotency_key`. The open-draft handler passes the header through
`with-idempotency-key`, and `new-version` stamps whatever `data`
carries, so a draft opened with an `Idempotency-Key` takes a
unique-index entry; a key reused across a create and a later open in
one bank, or an open retried after its draft was published, hits the
uniqueness violation, which `open-draft` has no read-back for, and
surfaces as an error anomaly — a 500. `update-version` rebuilds the
record from a fixed key set and does not carry `:idempotency-key`,
so the first update removes the create's index entry; the idempotency
TDD says a key reused after a day returns the original product off
the index, and after an update it mints a second product. That TDD
also says the open-draft handler "reads the optional key back off the
store index"; nothing does. `publish` and `discard` `assoc` onto the
existing record and keep the key. Traced, not executed. Evidence:
`update-version` and `new-version` in the write brick's
`domain.clj`, `with-idempotency-key` in the api base's product
`handlers.clj`, and the source-state guards section of
[idempotency.md](../idempotency.md).

Fix: carry `:idempotency-key` through `update-version`, stop stamping
it on open-draft or give open-draft the same read-back, and correct
the idempotency TDD's sentence. Add a replay-after-update test.

### F6. Medium — `template-id` is required then ignored, and drafts inherit it

The TDD says a new draft "starts from scratch field-wise; it doesn't
inherit from the previous version's data". `open-draft` reads the
template off the newest existing version and `update-draft` off the
version being updated; the request's `:template-id`, which the schema
requires on both routes, is never read. A client that names the
savings template on a current product gets a current draft and a 201.
Every version of a product therefore shares one template, and the
product type is fixed for the product's life — which is what the
migration brick's same-type rule and the account's snapshotted type
rely on, and what the TDD should say. An update also re-snapshots the
template as it stands now, so a template re-seeded with different
buckets reaches a draft on its next update; the proto's "later edits
to the template never change an existing product" holds for published
versions only. Traced, not executed. Evidence: `open-draft` and
`update-draft` in the write brick's `core.clj` and
`CashAccountProductRequest` in the api base's product
`components.clj`.

Fix: state that a product's template is fixed at creation and a
draft supplies name, currency, rate and window; then either drop
`:template-id` from the open and update requests or reject a
mismatch.

### F7. Medium — policy integration has a second limit and a third check

The TDD lists a draft capability on create and update, a publish
capability, and one count limit `{:aggregate :count :window :instant
:value <existing+1>}` keyed by bank. The window is
`:time-window-instant`. `discard` also runs the draft capability
check. `new-product` runs a second limit carrying `:product-type`, so
a policy limit whose filter enumerates types applies: the micro tier
allows 25 products and one per customer type, the platform 1,000 and
100, and the per-type cap scenario proves the 429. The total count
index groups by `[bank_id, product_id]` with no filter, so the
internal own-funds product — one per currency at bank creation —
consumes the total cap while `get-products` hides it, and a tenant
cannot see what is using its allowance. Evidence:
`check-product-type-limit` and `discard` in the write brick's
`domain.clj`,
[cash-account-products.yml](/components/resources/resources/policies/micro/restricted/limits/cash-account-products.yml)
under the micro policy, and `cash-account-products` in
[fdb-record-types.yml](/components/resources/resources/system/fdb-record-types.yml).

Fix: document both limits with the window name they use, the discard
check, and the internal products' share of the total cap; consider
excluding internal products from the count or showing them.

### F8. Medium — the PRD contradicts the TDD and the code on seven points

The PRD lists multi-currency products as a goal; the TDD and the
code pin one currency. It lists as non-goals a shared catalogue of
templates, effective-from and effective-to dating, retiring a
product, and repricing existing accounts; all four exist (F1, F2,
and the effective-dating section). Its "Creating a product" has the
tenant supplying product type, a list of currencies, payment-address
schemes and the balance-sheet side; the API takes a name, a
template id, one currency, a rate and a window. Its open question
that "the current banking API still accepts a balance-bucket layout
from the tenant" describes a field the request schema does not have.
Evidence: [cash-account-products.md](../../prd/cash-account-products.md)
and `CashAccountProductRequest` in the api base's product
`components.clj`.

Fix: rewrite the goals, non-goals and functional scope in product
terms around the template menu, effective dating, and migration, and
retire the open questions the code has answered.

### F9. Low — there is no 60-second product-version cache

The TDD says "the product-version cache — 60-second TTL, see
interest.md — sits in front of these reads on hot paths". The
interest pass memoises versions in an atom for the life of one run,
collected from the accounts as they stream, and its docstring says it
is "scoped to the run rather than a TTL cache" so a pass applies one
view of the rates; the interest TDD describes the same memo. No other
reader caches. Evidence: `get-product-version` in the interest
brick's `accrue.clj` and the alternatives section of
[interest.md](../interest.md).

Fix: drop the sentence, or describe the per-run memo and link it.

### F10. Low — not every consumer reads the version

The TDD says every operation on an account that needs product terms
"reads the version directly via `get-version`". Opening snapshots
`:product-type` onto the account record and onto each balance bucket,
and the payment brick reads the type off the debtor and creditor
accounts, never the version. The version is read at open
(`active-version`, currency, schemes, buckets), on address rotation
(schemes) and by interest (rate). A migration leaves the account's
`:product-type` alone, which holds only because of the same-type rule
in F1. Evidence: `open-account` and `migrate-product` in the
cash-account brick's `domain.clj`, and the `:product-type` reads in
the payment brick's `domain.clj`.

Fix: list which readers go to the version and which read the
snapshot, and note that the snapshot is what makes the migration
rule load-bearing.

### F11. Low — reads are capped, and the resolver allows a missing window

`get-product` reads at most 100 versions and `get-products` at most
1,000 rows per bank, and the API paginates the listing in memory
afterwards. The "discarded drafts accumulate" limitation therefore
has a consequence the TDD does not state: past those caps, versions
or whole products drop out of reads without an error. Separately,
`active-version` treats a missing `effective-from` as effective from
the beginning of time, while the TDD and `ensure-effective-window`
say it is required; the proto keeps the field optional. Evidence:
`get-product` in the query brick's `core.clj`, `get-versions` in its
`store.clj`, and `active-version` in its `domain.clj`.

Fix: state the caps or page the store scans, and say that the
resolver's nil case exists for records written before the field was
required.

## Missing evidence

- Nothing exercises the write brick against a record store. The
  domain test's docstring says it replaced "the slow integration
  paths in `interface_test.clj`", which no longer exists, and the
  brick's `application-test.yml` is referenced by no test. The unique
  index, the two count indexes, the template store and the
  idempotency read-back are untested below the API.
- No test races two creates at the cap (F4), replays a create after
  an update (F5), or sends an `Idempotency-Key` to open-draft (F5).
- No test names a different template on open-draft or update-draft
  (F6).
- No API scenario reaches `currency-not-allowed`, which the routes
  document, or `invalid-effective-window`; both are covered by the
  domain test only. No scenario lists the templates.
- No scenario opens an account against a future-dated or expired
  version, or with two published versions whose windows overlap; the
  effective-dating rule rests on the query brick's pure test, and
  every account-opening scenario resolves today against one version.
- No test asserts the total product cap, that internal products
  count toward it, or that the listing hides them; the list scenario
  matches with embeds.
- The property model covers create, publish, open-draft and discard
  by status and number; it has no update-draft verb and no notion of
  currency or effective dates.
- No test covers template re-seeding preserving `:created-at`.

## Recommended fixes

In order:

1. Add the migration path to the TDD and restate the cohort property,
   then fix the cash-accounts TDD's record comment and the PRD's
   non-goal (F1, F8).
2. Rewrite the data model and Architecture around the template
   record and the query split, citing ADR-0017 and ADR-0018 (F2, F3).
3. Make `new-product` atomic with its cap check and carry the
   idempotency key through `update-version`; add the race and replay
   tests (F4, F5).
4. Settle `template-id` on open and update, and document the fixed
   template per product (F6).
5. Document both limits, the discard check and the internal products'
   share (F7); correct the cache sentence, the reader list and the
   read caps (F9, F10, F11).
6. Add a brick-level integration test against FDB, and API scenarios
   for the currency rejection, the effective-dating cases and the
   template listing.

## References

- [cash-account-products.md](../cash-account-products.md) — Cash
  account products
- [cash-accounts.md](../cash-accounts.md) — Cash accounts
- [idempotency.md](../idempotency.md) — Idempotency
- [interest.md](../interest.md) — Interest
- [policy-evaluation.md](../policy-evaluation.md) — Policy evaluation
- [cash-account-products.md](../../prd/cash-account-products.md) —
  Cash account products (PRD)
- [cash-account-migration.md](../cash-account-migration.md) —
  Migrating cash accounts between products (plan)
- [ADR-0017](../../adr/0017-query-write-brick-split.md) — Query/write
  brick split for domain components
- [ADR-0018](../../adr/0018-command-writes-are-earned.md) — Command
  writes are earned, not default
