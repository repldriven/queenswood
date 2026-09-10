# Cash account migration

## Objective

A cash-account product version applies to accounts opened while it is
in force, and an account keeps the version it opened on. Moving an
existing account onto different terms is a cohort operation with
consent and notice requirements behind it, not an edit — see
[cash-account-products.md](cash-account-products.md) for the pin this
moves.

This TDD describes the migration resource, what makes a source and a
target compatible, when a migration may run, the records it writes,
and where it sits in the API.

In scope: the `cash-account-migration` brick, its lifecycle and its
per-account rows, the scheduler task that commits a migration, and
the API surface. Out of scope: the product and version model itself,
see [cash-account-products.md](cash-account-products.md); the chunked
pass this reuses, see [interest.md](interest.md).

## Background

The scheduler carried a placeholder for the work before any of this
existed. Its task registry defined
`:scheduler-task-kind-account-migration` as a no-op returning
`{:migrated 0}`, and the seeded default jobs ran it on a cadence in
every bank. So the trigger existed and fired, and what it did not have
was anything to do.

The first shape considered was a flag on the product version: a field
saying "adopt the accounts on my predecessor", read by a job that
inferred its work by finding accounts not on the version in force. It
was drafted and abandoned — see **Alternatives Considered**.

## Proposed Solution

### A migration is its own resource

A migration carries an explicit source and target, its own lifecycle
and dates, and its own per-account record of what happened.

Three properties fall out, and each is load-bearing.

**A preview is the only thing the API can do.** Creating and previewing
a migration are ordinary API operations. Performing one is not — the
only thing that commits a migration is the scheduler task, whether it
fires on its date or is forced by hand. No request can move a hundred
thousand accounts. The scheduler already records
`SchedulerTriggerSource` as `scheduled` or `forced`, so a migration run
by hand is distinguishable from one that fired on its date without any
new machinery.

**The preview and the commit are one code path.** A pass that computes
per-account decisions, and a single flag deciding whether it writes the
repin. If the two diverge at all the preview stops being evidence, so
the selection, the eligibility evaluation and the per-account outcome
rows are identical in both, and only the write to the account is
conditional.

**Eligibility is discovered per account, at run time.** A migration is
not rejected up front because some of its accounts do not fit. Each
account is evaluated on its own and the ones that do not qualify are
recorded with a reason. This is what makes a preview worth reading: the
interesting output is not that 9,588 accounts would move, it is that
412 would not, and why.

### What compatibility means

One rule: the source and target must be the same `product_type`. A
savings product migrates to a savings product. Nothing else about the
target has to resemble the source.

Everything else that could differ is an eligibility question, not a
compatibility one, and belongs to the individual account rather than to
the migration. A GBP account being moved to a product that allows only
EUR does not make the migration invalid — it makes that account
ineligible, and every other account in the cohort still moves.

The distinction matters because it decides when a problem surfaces. A
compatibility rule is checked once, at creation, against two records.
An eligibility rule is checked per account, during the pass, against
live state that nothing has frozen.

### When a migration may run

A migration may only run while the target version is in force —
`effective-from` has passed and `effective-to` has not. This is the
same window `active-version` in `cash-account-product-query` uses to
decide what a newly opened account pins to, so an account that
migrates and an account that opens on the same day land on the same
terms.

Runnability is derived from that window every time the job looks, not
decided once and recorded. A migration whose target is not in force
today is not due today, which is a different thing from being dead: the
window it is measured against can move, and the migration moves with
it. Point a migration at something effective today, push that date out
a week, and the migration simply becomes due a week later. Nothing
about it needed changing.

So a closed window should not latch a terminal state. A migration that
can no longer run is one an operator cancels, because the system cannot
tell a target whose dates slipped from one nobody intends to use. What
the job can do is say which of the migrations it holds are not due and
why, so a migration waiting on a date is distinguishable from one
waiting on nothing.

A migration names a target version rather than a target product, so
the window it is measured against is the one that version carries. A
published version's effective dates cannot be edited, so the window
moves only if the version does, and a migration's own status is the
only other thing that changes about it.

### The records

Three, following the shape the interest pass already uses — a
lifecycle record for the migration, a record per run, and one row per
account.

**The migration** (`CashAccountMigration`) carries the bank, its own
id, the source (a product, optionally narrowed to particular
versions), the target (a product and version), its status, the date
customers were notified, the date it becomes due, and the counts of
what it moved.

**The run** (`CashAccountMigrationRun`) is one preview or one commit:
which migration it belongs to, whether it was a dry run, the business
day it ran on, its status and its totals. A count index over
`[bank_id, business_day, dry_run]` is what makes the daily preview
limit one read rather than a scan.

**The account rows** (`CashAccountMigrationAccountRun`) carry one
entry per account the pass considered: the account, the version it was
on, the version it was moved to, the outcome, and — for an account
that did not move — the reason. The rows are written by a preview as
well as a commit, which is what makes a preview inspectable per
account rather than a summary. A row is keyed by its run, so a
preview's rows and a commit's rows do not overwrite each other and can
be compared, and a count index over `[bank_id, run_id, outcome]`
answers the totals a preview is read for without reading the rows.

Rows also make the pass resumable and chunked for the same reason they
do in interest: an account already moved is skipped on a re-run, and a
failure isolates to its chunk rather than ending the migration.

### Why a preview is a forecast, not a promise

Accounts close. New accounts open on the source product. Balances move,
and a balance-dependent eligibility rule moves with them. A preview run
on Monday and a commit run on Friday will not agree, and no amount of
care makes them.

The honest design accepts this rather than hiding it. A preview can be
re-run as often as wanted, right up to the moment of commit. Approval
attaches to the migration — to its source, target and selection — and
not to any particular preview's numbers. The commit writes its own rows,
so the difference between what was expected and what happened is
readable afterwards rather than assumed away.

### The scheduler task

The placeholder has a real work list: migrations that are approved and
due, whose target version is in force. That is a query, not an
inference — the resource is the work item, and there is nothing to
derive from the state of the accounts themselves.

The task inherits what a scheduler run already records: per-task
timings, status, and the processed and failed counts that surface in
the job history. What it reports is the run's total rather than a
figure per migration, because the scheduler models one task per kind
and not one per work item. Where several migrations fall due on the
same day, the split between them is read from the migrations
themselves, each of which holds its own run and its own per-account
rows.

### Where it sits in the API

A top-level `cash-account-migrations` collection, alongside
`cash-accounts` and `cash-account-products` rather than beneath either.

Not a sub-resource of a product, because a migration names two of them
and neither owns it. Hanging it off the target would assert that the
product receiving accounts owns the fact that another product is losing
them, which is false, and becomes more obviously false when several
products feed one. Hanging it off the source has the same problem
mirrored. It is also the bank-shaped reading: what an operator wants to
ask is which migrations are in flight, not which migrations a given
product has.

```
POST   /v1/cash-account-migrations                     author
GET    /v1/cash-account-migrations                     list
GET    /v1/cash-account-migrations/{id}                read
POST   /v1/cash-account-migrations/{id}/approve        authorise it
POST   /v1/cash-account-migrations/{id}/cancel         stop it
POST   /v1/cash-account-migrations/{id}/previews       run a preview
GET    /v1/cash-account-migrations/{id}/previews       list previews
GET    /v1/cash-account-migrations/{id}/previews/{rid} what it would do
GET    /v1/cash-account-migrations/{id}/previews/{rid}/accounts
                                                       per-account, with reasons
```

A preview is a sub-collection for the same reason a scheduler run is:
creating one under its parent and reading it back by id is already how
`/v1/jobs/{job-id}/runs` works. Approval and cancellation are
transitions of the migration rather than sub-collections, so both are
actions on the resource that return it in its new status.

What is absent matters more than what is present. There is no
`POST /v1/cash-account-migrations/{id}/runs`. Committing is
`POST /v1/jobs/{job-id}/runs` against the migration job, so the rule
that only the scheduler moves accounts is visible in the shape of the
API rather than being a convention a reader has to be told. Forcing that
job runs every migration that is due rather than a chosen one — which is
consistent, since it is the same operation the schedule performs, but
worth knowing before someone expects to force just one.

A product version answers nothing about migrations. The index that
would say which migrations target a version exists on the migration
record, and no read uses it — see **Known limitations**.

### Whether this generalises, and where the seam is

Account migration is not the only bulk change of this shape, so it is
worth being deliberate about what gets built once and what gets built
for accounts.

Three adjacent cases already have a claim on it. Deriving balance-bucket
layouts from product type is named as a direction in the product
requirements, and moving existing accounts onto derived layouts is a
cohort operation with a per-account outcome. Backfilling a balance an
account should have but does not is the same operation again — the
accrual pass already logs the case where an account carries a non-zero
rate and no accrued balance to put it in, and nothing today can fix that
fleet-wide. Reissuing payment addresses, which `rotate-address` does one
account at a time, becomes this when a bank changes sort code or
clearing arrangement and every account it holds needs new ones.

Retiring a product is the pair to migration rather than another
instance: close a product to new business, then move whoever is left.

One thing that shares the word and none of the concern is the migrator,
which applies FDB record metadata at deploy time. Schema migration and
cohort migration are different problems, and the vocabulary should keep
them apart. A bank changing tier is different again — it rewrites one
record, and wants none of this.

The generalisation, then, is not "migration". Two separable things live
here and only one of them is common.

**The bulk pass is already general.** Scanning a bank's accounts in
chunks, deciding per record, writing, recording a row per record,
reporting counts, and skipping what is already done on a re-run — that
exists twice for interest and is extracted. The scan takes the
per-account function off its context and knows nothing about interest,
so a migration is a third pass configuration beside accrual and
capitalisation rather than new machinery. This half generalised without
anybody deciding to generalise it, which is the good case.

**The approval envelope is not general, and should not be made so.**
Preview, notice, approval and scheduler-only commit exist because a
change is adverse and visible to a customer. Interest accrual wants none
of it. A balance-layout backfill wants the preview and no notice,
because nobody outside the bank can see the change. Reissuing addresses
wants the whole thing.

So: build account migration concretely, on the existing scan, and treat
the envelope as the thing to watch. If address reissue arrives wanting
the same preview-notice-approve-commit sequence, that is the moment to
lift it out — with two real instances to shape it rather than one and a
guess.

## Alternatives Considered

- **A flag on the published version.** A field saying "adopt the
  accounts on my predecessor", read by a job that inferred its work
  from the accounts. Rejected on two counts. A version has one date and
  a migration needs three: `effective-from` says when new accounts get
  the terms, while moving existing customers onto terms less favourable
  than the ones they hold requires telling them in advance, so the date
  they are notified and the date they move are both distinct from it
  and from each other. And a flag can only ever mean "earlier versions
  of my own product", where the case that motivates the feature is
  splitting a product line — moving a cohort from one savings product
  to a different savings product, which needs source and target named
  independently.
- **A target that is a product rather than a version of one.** Rejected
  — approval means these accounts move to these terms, and a floating
  target would let a version published afterwards change what was
  agreed. A published version's effective dates cannot be edited
  either, so the window the migration is measured against is fixed with
  it.
- **A general preview-notice-approve-commit envelope.** Rejected for
  now — the bulk pass underneath is already shared with the interest
  accrual and capitalisation passes, but the envelope exists because a
  change is adverse and customer-visible, which is true of this and of
  none of the passes beside it.
- **Committing a migration through the API.** Rejected — no request
  moves a hundred thousand accounts. The scheduler task is the only
  thing that commits, whether it fires on its date or an operator
  forces it.

## Known Limitations

- **The cohort is the source product, not a query.** A migration names
  a source product and may narrow it to particular source versions.
  There is no stored query (currency, balance band, party segment) and
  no explicit account list. A query re-evaluates at run time and is
  more useful; an explicit list is easier to defend, because the set
  that moved is the set that was approved. Both are open, and the two
  can be combined — resolve a query at creation and freeze the result —
  at the cost of a cohort that goes stale between approval and commit.
- **Notice has no minimum gap.** Approval refuses a migration without
  both a notice date and a due date, and refuses one whose notice falls
  after the move, but whatever gap it is told is what it records. The
  platform enforces no minimum.
- **Migrations are bank-scoped.** A bank moving its own customers
  between its own products is an ordinary bank-scoped resource. A
  platform-initiated migration is closer to policy and would need a
  different authorisation story, which does not exist.
- **A version answers nothing about the migrations that target it.**
  The `CashAccountMigration_by_target_version` index carries the
  relationship and no read uses it, so the products API cannot say
  which migrations point at a version, or how many accounts sit on
  one.
- **No convenience path from publishing.** A "bring existing accounts
  along" option when publishing a version could mint a migration with a
  default notice period. That is sugar over the resource rather than an
  alternative to it, and nothing offers it.

## References

- [cash-account-products.md](cash-account-products.md) — Cash account
  products (the version lifecycle and effective dating this builds on)
- [cash-accounts.md](cash-accounts.md) — Cash accounts (the pin a
  migration moves, and the product-type snapshot it leaves alone)
- [interest.md](interest.md) — Interest accrual (the chunked-pass and
  per-account-row shape reused here)
- [policy-evaluation.md](policy-evaluation.md) — Policy evaluation
  (the commit and preview limits)
- [ADR-0018](../adr/0018-command-writes-are-earned.md) — command
  writes are earned (why the migration writes stay synchronous)
- [PRD: cash-account-products](../prd/cash-account-products.md) —
  the consent and notice requirements behind repricing
- `cash-account-migration` brick interface
