(ns com.repldriven.queenswood.scheduler.interface
  "Per-bank job scheduler. Jobs are preset sequences of tasks (accrue,
  capitalize, future account-migration) seeded into a bank at
  provisioning, each on a Quartz cron schedule the operator may edit
  within what its tasks allow. The `:bank-scheduler/runner` system component
  keeps a cronut trigger per active job in step with the job rows, at
  startup and every minute after, so a bank created after it started,
  a job added to `jobs.edn` after the bank, and an edit made through
  the API in another JVM all reach the live scheduler; FDB is the
  source of truth for jobs and runs.

  `config` throughout is the FDB+interfaces map (`:record-db`,
  `:record-store`, `:schemas`, and — for trigger edits — `:scheduler`)."
  (:require
    [com.repldriven.queenswood.scheduler.system]

    [com.repldriven.queenswood.scheduler.core :as core]
    [com.repldriven.queenswood.scheduler.domain :as domain]))

(defn validate-schedule
  "Nil where a job of `task-kinds` may run on `schedule`, a Quartz cron
  expression in UTC, judged on its next fire times after `now`:
  accrue must fire exactly once a day, and capitalize and
  account-migration no more than once a day. Otherwise a
  `:scheduler/invalid-schedule` rejection for a schedule that does not
  parse or never fires, or `:scheduler/periodicity-not-allowed`. Pure,
  so callers (the API, the console) can offer only what is allowed.

  Args:
  - task-kinds: the job's ordered task kinds.
  - schedule: a Quartz cron expression.
  - now: epoch millis to sample from."
  [task-kinds schedule now]
  (domain/validate-schedule task-kinds schedule now))

(defn seed-jobs
  "Seed `bank-id`'s default scheduled jobs (FDB only — no triggers).
  Idempotent by `[bank_id, job_id]`. Run inside the caller's
  transaction (e.g. bank creation) so a failure rolls back with it.

  Args:
  - txn: an open FDB transaction or a config map.
  - bank-id: the bank to seed jobs for."
  [txn bank-id]
  (core/seed-jobs txn bank-id))

(defn force-start
  "Run `job-id` now (trigger source forced), as the run for the last
  fire of its schedule. Returns the final run map or an anomaly. A run
  of the job that is running or completed for that fire refuses it with
  `:scheduler/period-already-run`; a failed one does not, so a failed
  run may be forced again. The run records the `actor` that forced it.

  Args:
  - config: FDB+interfaces map.
  - bank-id: the bank owning the job.
  - job-id: the job to run.
  - actor: who forced the run."
  [config bank-id job-id actor]
  (core/force-start config bank-id job-id actor))

(defn update-schedule
  "Edit a job's `:schedule` and/or `:status`, the schedule within what
  its tasks allow, recording who did as `updated-by`. Persists,
  recomputes next-run, and updates the live trigger when `config` has
  `:scheduler`. System jobs have a fixed cadence — only the time of day
  in their schedule is editable; changing the days it fires on, or the
  status, is rejected.
  Returns the updated job or an anomaly.

  Args:
  - config: FDB+interfaces map.
  - bank-id: the bank owning the job.
  - job-id: the job to edit.
  - edits: map of any of `:schedule` `:status`.
  - actor: who made the edit."
  [config bank-id job-id edits actor]
  (core/update-schedule config bank-id job-id edits actor))

(defn list-jobs
  "One page of `bank-id`'s scheduled jobs, in job-id order. Returns
  `{:jobs [...] :before id|nil :after id|nil}`, the cursors set only
  where jobs lie on that side of the page, or an anomaly.

  Args:
  - config: FDB+interfaces map.
  - bank-id: the bank to list jobs for.
  - opts (optional): `:after`, `:before` and `:limit` (default 1000)."
  ([config bank-id] (core/list-jobs config bank-id nil))
  ([config bank-id opts] (core/list-jobs config bank-id opts)))

(defn get-job
  "One job by id, or nil if absent.

  Args:
  - config: FDB+interfaces map.
  - bank-id: the bank owning the job.
  - job-id: the job to fetch."
  [config bank-id job-id]
  (core/get-job config bank-id job-id))

(defn list-runs
  "Runs of `job-id`, newest first.

  Args:
  - config: FDB+interfaces map.
  - bank-id: the bank owning the job.
  - job-id: the job whose runs to list."
  [config bank-id job-id]
  (core/list-runs config bank-id job-id))

(defn get-run
  "One run by id, or nil if absent.

  Args:
  - config: FDB+interfaces map.
  - bank-id: the bank owning the run.
  - run-id: the run to fetch."
  [config bank-id run-id]
  (core/get-run config bank-id run-id))
