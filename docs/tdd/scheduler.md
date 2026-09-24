# Scheduled runs

> **Status: proposal.** The scheduler, its jobs API, the four task kinds
> and the resumable interest pass exist. The Proposed Solution is the
> build list, and [First slice](#first-slice) says what comes first.

## Objective

Make a scheduled job run to completion: every day an interest job is
owed runs once, whether the process was up when it fell due, crashed
part-way through it, or failed on some of the bank's accounts.

In scope: the as-of date a run carries, a run queued as a row and
executed by the one dispatcher under a lease, catch-up of the slots a
job missed, retrying the accounts an interest pass failed, and
force-start over the API as a queued run.

Out of scope: the interest arithmetic and the ledger entries, which
[interest](interest.md) decides; what the rewards and migration tasks
do inside their passes, which [rewards](rewards.md) and
[cash-account-migration](cash-account-migration.md) decide; and a
progress route for an interest run in flight.

## Background

- **A job is a row, and a trigger is memory.** `SchedulerJob` rows are
  seeded per bank from
  [jobs.edn](/components/resources/resources/scheduler/jobs.edn). The
  `bank-scheduler/runner` in the
  [scheduler](/components/scheduler/src/com/repldriven/queenswood/scheduler/core.clj)
  brick registers an in-memory Quartz trigger per enabled job and
  reconciles the triggers against the rows every minute. It runs in
  `exclusive-dispatchers-service` at one replica, and in the monolith.
- **A run takes today's date when it fires.** `run-job` reads
  `utility/today` and passes it to every task. `SchedulerRun` records
  status, per-task counts and timings, but not the date the run was
  for.
- **A crashed run stays running.** `run-job` writes the run `running`,
  then writes it again after each task. A process that dies between
  those writes leaves a `running` row that nothing reads again, and the
  next fire is the next day's.
- **A missed fire is lost.** Quartz holds its triggers in memory, so a
  process that is down when a trigger falls due never fires it, and
  nothing compares `next_run_at` with the clock.
- **Force-start runs in the request.** `POST /v1/jobs/{job-id}/runs`
  calls `scheduler/force-start` in the API's thread and returns 201 with
  the finished run, in a different JVM from the scheduled runs.
- **The interest pass is resumable per date.** `accrue-day` and
  `capitalize-accrued` post a chunk of accounts per transaction and mark
  each account DONE in the same transaction, so a re-run of the same
  date skips what is done. The `InterestRun` record is written only at
  close, so a crashed pass does not count against the daily limit. See
  [interest](interest.md).
- **A failed chunk is never retried.** A chunk that fails marks its
  accounts FAILED, and a re-run skips any row that is not PENDING. The
  pass then closes: it posts the bank's side from a sum over DONE rows
  under a key per day and currency, writes the `InterestRun` record, and
  reports success with `accounts-failed` set. The job records
  `succeeded`.
- **Rewards and migrations are work lists.** `reward/pay-due` pays every
  account owed a reward, and `run-due-migrations` commits every approved
  migration that is due. Neither owes anything per date, so the next run
  does what a missed one would have.

## Proposed Solution

### Dated and level tasks

A task in the registry in
[core.clj](/components/scheduler/src/com/repldriven/queenswood/scheduler/core.clj)
declares `:catch-up`:

- `:every-slot` for accrual and capitalisation. Each slot is a business
  day owed on its own, and its as-of date keys the postings.
- `:latest` for rewards and migrations. The newest slot covers every
  earlier one.

A job owes every slot it missed where any of its tasks is
`:every-slot`, and only the newest otherwise.

A task's contract, which all four meet once the interest change below
lands:

- Running a task twice for one bank and as-of date posts once.
- A task returns an anomaly while any of its work is outstanding, so the
  run is retried rather than recorded `succeeded`.

### The run as a queued row

`SchedulerRun` in
[scheduler-run.proto](/components/schema/resources/schemas/scheduler/scheduler-run.proto)
gains:

- `SCHEDULER_RUN_STATUS_QUEUED`.
- `as_of_date`, an epoch-day, which every task is passed in place of
  `utility/today`.
- `due_at`, the slot the run answers: the cron time for a scheduled run
  and the request time for a forced one.
- `lease_owner`, `lease_expires_at` and `attempts`.

The schema also gains an index on `SchedulerRun` by status and lease
expiry across banks, and a unique index on `[bank_id, job_id, due_at]`,
with a metadata version bump per
[schema-evolution](../recipes/code/schema-evolution.md).

A run is written `queued` and executed by whoever claims it. Nothing
executes a run it did not queue and claim through the row.

### One sweep enqueues due slots

The per-job Quartz triggers go, and with them `register!`, the
`:triggers` atom and the trigger ids. The runner's one-minute sweep
reads every enabled job whose `next_run_at` is at or before now and, in
one transaction per job:

- writes a `queued` run for each slot the job owes — every slot from
  `next_run_at` to now for an `:every-slot` job, the newest alone for a
  `:latest` one;
- advances `next_run_at` to the first slot after now.

The unique index makes the enqueue idempotent, so two sweeps reaching
one job, on two pods during a rollout, write one run per slot.
`scheduler.runner.catch-up-days` (default 7) bounds how far back a
sweep enqueues. A job further behind is enqueued from the bound and
logged.

The sweep also seeds missing jobs, as the reconcile does now.

### Claiming a run under a lease

The runner's executor claims the oldest `queued` run, or a `running` one
whose lease has expired, from a bank with no run under a live lease. The
claim is one transaction that reads the bank's runs and writes
`running`, `lease_owner`, `lease_expires_at` and `attempts + 1`, so two
claimants conflict and one retries.

A heartbeat renews the lease every `scheduler.runner.lease-renew-ms`
(default 10000) while the run executes, to
`scheduler.runner.lease-ms` (default 60000). A process that dies stops
renewing, and its run becomes claimable once the lease expires. That
is the crash recovery.

A claimed run executes from the first task not `succeeded`, with the
run's `as_of_date`. A task anomaly writes the run back to `queued`
until `attempts` reaches `scheduler.runner.max-attempts` (default 5),
then `failed`, leaving an operator to force it again.

`scheduler.runner.workers` (default 4) executor threads run runs for
different banks at once. A bank runs one at a time, oldest `due_at`
first, so a caught-up accrual day posts before the next.

### Interest closes only when complete

In
[scan.clj](/components/interest/src/com/repldriven/queenswood/interest/scan.clj),
`post-account` skips only a DONE row, so a FAILED account is retried
on the next pass for the same date.

In
[core.clj](/components/interest/src/com/repldriven/queenswood/interest/core.clj),
`run-interest` posts the bank's side and writes the `InterestRun`
record only when the tally has no failures. Otherwise it returns
`:interest/run-incomplete`, an `error/fail` carrying the done and
failed counts, and the scheduler records the task failed with
`records_failed` and queues the run again.

The bank's side is posted under a key per day and currency, and a
second post with that key returns the first outcome. Closing only when
every account is DONE keeps accounts completed on a retry out of a sum
that has already been posted.

### Force-start over the API

In
[handlers.clj](/bases/api/src/com/repldriven/queenswood/api/jobs/handlers.clj),
`POST /v1/jobs/{job-id}/runs` writes a `queued` run and returns 202 with
the run and its `Location`. The body may carry `as-of-date`, an ISO
date, to re-run a past day:

- A future date is refused with 422, `:scheduler/invalid-as-of-date`.
- A date before `catch-up-days` is refused the same way.
- A run of the job already queued or running for that date is refused
  with 409, `:scheduler/run-in-progress`.

`scheduler/force-start` becomes `scheduler/enqueue-run`. The console
reads the run's status from `GET /v1/jobs/{job-id}/runs/{run-id}`,
which it already polls for the job's badge.

### First slice

Interest closes only when complete, and retries FAILED accounts. It
touches only the interest brick and needs no schema change, and it
stops the one loss that happens silently today. The run schema, the
sweep and the executor follow as one change, and the API's 202 last,
since it needs the executor to run what it queues.

### Tests

- **interest** — a pass whose chunk fails returns
  `:interest/run-incomplete` and writes no `InterestRun`. A second
  pass for the date posts the failed accounts, closes, and posts the
  bank's side once, covering every account.
- **scheduler** — the sweep enqueues one run per missed slot for an
  `:every-slot` job and one for a `:latest` job, and the same sweep
  twice enqueues nothing new. A claim conflicts with a concurrent
  claim. A run whose lease has expired is claimed again and resumes
  from its first unfinished task with its own `as_of_date`. A run
  failing `max-attempts` times is `failed`.
- **test-scenarios** — `:force-start-job` enqueues and drains the
  queue, and the model-equality property test runs a crash between
  chunks followed by a resumed run.
- **test-api-scenarios** — the rig includes `system/scheduler.yml`,
  the seven scenarios that force a job wait for the run to finish, and
  `jobs/` covers the 202, a past `as-of-date`, and the 409 and 422
  refusals.

## Alternatives Considered

- **Keep the interest command on the bus for its redelivery.**
  Rejected: nothing sends it, a pass lasting minutes outlives a
  consumer's redelivery budget, and a bus has no notion of a day that
  was never sent. It was removed.
- **Treat a `running` row as orphaned when the runner starts.**
  Rejected: the dispatcher deploys with a rolling update, so a new pod
  starts while the old one is still running a pass.
- **Keep a Quartz trigger per job, with a misfire policy.** Rejected:
  the in-memory store forgets a misfire across the restart that caused
  it, and a JDBC job store is a second database holding what the job
  row already says.
- **Close an incomplete run and post a correcting entry later.**
  Rejected: the correction needs a key of its own and a record of what
  the first entry covered, where closing once complete needs neither.
- **Keep force-start synchronous with a longer timeout.** Rejected: a
  pass over a bank's accounts outlasts any request timeout, and it
  would run beside the dispatcher's runs rather than behind them.

## Known Limitations

- **A late day accrues on the balance when it runs.** Nothing records
  a balance as of a past day, so a caught-up accrual computes on the
  balance at the time of the catch-up.
- **A day's accrual runs before the day ends.** The default job runs
  at 17:00 UTC, so a movement after it falls into the next day's
  accrual.
- **A run starts up to a sweep interval late.** The sweep runs every
  minute, so a run waits up to a minute after its cron time or its
  force-start.
- **An account that fails every attempt holds its day open.** The
  bank's side for that date waits for an operator, while later days
  close independently.

## References

- [platform](../prd/platform.md) — the operator who schedules accrual
  and capitalisation.
- [interest](../prd/interest.md) — the daily accrual and capitalisation
  cadence the jobs carry out.
- [interest](interest.md) — the resumable pass, the daily limit and the
  close.
- [rewards](rewards.md) — the reconcile and the hourly periodicity this
  replaces the triggers of.
- [cash-account-migration](cash-account-migration.md) — the migration
  task.
- [idempotency](idempotency.md) — the keys the close and capitalisation
  post under.
- [ADR-0019](../adr/0019-processor-packaging.md) — the dispatcher
  service the runner lives in.
- [schema-evolution](../recipes/code/schema-evolution.md) — the
  metadata version bump for the new fields and indexes.
- [deployment](../recipes/infra/deployment.md) — why the dispatcher
  stays at one replica.
