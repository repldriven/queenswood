(ns com.repldriven.queenswood.cash-account-migration.interface
  "Planned moves of a product's cash accounts onto a different product
  version.

  A migration is a statement of intent, not an operation. Authoring one
  moves nothing, and neither does approving it — the only thing that
  moves accounts is the scheduler's migration task. That split is
  deliberate: a cohort change is adverse and customer-visible, so it
  wants notice, an approval with an identity, and a preview beforehand,
  none of which a single write expresses.

  Reads and writes live in one brick rather than the query/write pair,
  because these writes are synchronous. They span one record, nothing
  reacts to them, and they do not arrive over an unreliable ingress, so
  they earn no command — and the split follows commands.
  See [ADR-0018](../../../../docs/adr/0018-command-writes-are-earned.md)."
  (:require
    [com.repldriven.queenswood.cash-account-migration.core :as core]))

(defn create-migration
  "Author a migration in draft. Checks the one compatibility rule — that
  source and target are the same product type — and that the target is
  a published version. Everything else that could stop a given account
  moving is decided per account when the migration runs.

  Idempotent on `:idempotency-key`: a retry reads the existing migration
  back rather than authoring a second one against the same accounts.

  Args:
  - txn: FDB transaction or db handle.
  - data: map with `:bank-id`, `:source-product-id`,
    `:target-product-id`, `:target-version-id`; optionally
    `:source-version-ids` to narrow the cohort to accounts on those
    versions (every version of the source product when absent),
    `:notified-on` and `:due-on` (epoch-day), and `:idempotency-key`.

  Returns the migration map or an anomaly."
  [txn data]
  (core/create-migration txn data))

(defn get-migration
  "One migration by id, or a `:cash-account-migration/not-found`
  rejection.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - migration-id: the migration's id."
  [txn bank-id migration-id]
  (core/get-migration txn bank-id migration-id))

(defn list-migrations
  "One page of a bank's migrations, newest first. Returns
  `{:migrations [...] :before id|nil :after id|nil}`, the cursors set
  only where migrations lie on that side of the page.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - opts (optional): `:after`, `:before`, `:limit` (default 1000) and
    `:order` (`:desc` by default)."
  ([txn bank-id]
   (core/list-migrations txn bank-id))
  ([txn bank-id opts]
   (core/list-migrations txn bank-id opts)))

(defn preview-migration
  "Evaluate a migration without moving anything, recording a verdict per
  account in its cohort. Returns the closed run.

  A preview is a forecast, not a promise. Accounts open and close and
  balances move between one and the commit, so previews may be re-run as
  often as wanted — approval attaches to the migration, never to a
  particular preview's numbers.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - migration-id: the migration to evaluate.
  - business-day: epoch-day the run is recorded against.

  Returns the run map — `:accounts-seen`, `:accounts-moved`,
  `:accounts-ineligible` — or an anomaly."
  [txn bank-id migration-id business-day]
  (core/preview-migration txn bank-id migration-id business-day))

(defn approve-migration
  "Approve a draft, making it work the scheduler will pick up on its due
  date. Requires a notice date and a due date — approving commits to
  moving customers' accounts, and doing that without having told them is
  what notice exists to prevent.

  Approving moves nothing. Only the scheduler's migration task does.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - migration-id: the migration to approve.

  Returns the approved migration, a
  `:cash-account-migration/invalid-status` rejection when it is not a
  draft, or `:cash-account-migration/notice-required` when either date
  is missing."
  [txn bank-id migration-id]
  (core/approve-migration txn bank-id migration-id))

(defn cancel-migration
  "Cancel a draft or an approved migration, taking it off the work list.

  This is how a migration that can no longer run is retired: the system
  cannot tell a target whose effective dates slipped from one nobody
  intends to use, so it keeps waiting until an operator says otherwise.
  A completed migration cannot be cancelled — its accounts have moved.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - migration-id: the migration to cancel.

  Returns the cancelled migration or a
  `:cash-account-migration/invalid-status` rejection."
  [txn bank-id migration-id]
  (core/cancel-migration txn bank-id migration-id))

(defn commit-migration
  "Run an approved migration for real, moving every eligible account onto
  the target version, then complete it. Returns the closed run.

  The decisions are the preview's decisions — same streaming, same cohort
  test, same eligibility order — so what a preview reported is what a
  commit acts on, allowing for the population having moved underneath
  both. An account that fails is recorded against its own row and the
  pass carries on.

  The migration completes only when the pass finished; a pass that could
  not run leaves it approved for the next business day.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - migration-id: the migration to run.
  - business-day: epoch-day the run is recorded against."
  [txn bank-id migration-id business-day]
  (core/commit-migration txn bank-id migration-id business-day))

(defn run-due-migrations
  "Commit every migration of the bank that is due on `business-day` — the
  scheduler's migration task.

  Due is derived, not recorded: a migration is due when it is approved,
  its due date has arrived, and its target version is in force. A
  migration whose target window has not opened is not due today, which
  is a different thing from being dead, so nothing latches.

  One migration failing does not stop the others.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - business-day: epoch-day to run for.

  Returns a summary — `:migrations`, `:moved`, `:ineligible`,
  `:failed-migrations`, plus the `:accounts-processed` and
  `:accounts-failed` the scheduler records against the task — or an
  anomaly."
  [txn bank-id business-day]
  (core/run-due-migrations txn bank-id business-day))

(defn get-run
  "One run of a migration, preview or commit, or a
  `:cash-account-migration/run-not-found` rejection.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - run-id: the run's id."
  [txn bank-id run-id]
  (core/get-run txn bank-id run-id))

(defn list-runs
  "Every run of one migration, newest first.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - migration-id: the migration whose runs to list."
  [txn bank-id migration-id]
  (core/list-runs txn bank-id migration-id))

(defn list-run-accounts
  "One page of the per-account verdicts one run recorded, in account
  order. This is what a preview is read for: which accounts would not
  move, and why. Returns `{:account-runs [...] :before id|nil :after
  id|nil}`, the cursors set only where verdicts lie on that side.

  Args:
  - txn: FDB transaction or db handle.
  - bank-id: owning bank id.
  - run-id: the run whose verdicts to list.
  - opts (optional): `:after`, `:before` and `:limit` (default 1000)."
  ([txn bank-id run-id]
   (core/list-run-accounts txn bank-id run-id))
  ([txn bank-id run-id opts]
   (core/list-run-accounts txn bank-id run-id opts)))
