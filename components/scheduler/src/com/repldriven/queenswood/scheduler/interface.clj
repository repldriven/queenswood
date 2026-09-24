(ns com.repldriven.queenswood.scheduler.interface
  "Per-bank job scheduler. Jobs are preset sequences of tasks (accrue,
  capitalize, future account-migration) seeded into a bank at
  provisioning and editable by the operator within task-defined
  periodicity limits. The `:bank-scheduler/runner` system component
  keeps a cronut trigger per enabled job in step with the job rows, at
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

(defn allowed-periodicities
  "The periodicities a job built from `task-kinds` may use — the
  intersection of its tasks' allowed periodicities (accrue is daily-only;
  capitalize and account-migration allow daily/monthly/yearly; hourly
  is a periodicity no seeded task takes yet). A pure
  derivation, surfaced so callers (the API, the console) can constrain
  the cadence picker without duplicating the rule.

  Args:
  - task-kinds: the job's ordered task kinds."
  [task-kinds]
  (domain/job-allowed-periods task-kinds))

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
  "Run `job-id` now (trigger source forced). Returns the final run map
  or an anomaly. Safe to repeat — tasks are idempotent.

  Args:
  - config: FDB+interfaces map.
  - bank-id: the bank owning the job.
  - job-id: the job to run."
  [config bank-id job-id]
  (core/force-start config bank-id job-id))

(defn update-schedule
  "Edit a job's `:periodicity`, `:monthly-day`, `:run-time-minutes`,
  and/or `:enabled`, within the periodicities its tasks allow. Persists,
  recomputes next-run, and updates the live trigger when `config` has
  `:scheduler`. System jobs have a fixed cadence — only `:run-time-minutes`
  is editable; touching `:periodicity` / `:monthly-day` / `:enabled` on
  one is rejected. Returns the updated job or an anomaly.

  Args:
  - config: FDB+interfaces map.
  - bank-id: the bank owning the job.
  - job-id: the job to edit.
  - edits: map of any of `:periodicity` `:monthly-day` `:run-time-minutes`
    `:enabled`."
  [config bank-id job-id edits]
  (core/update-schedule config bank-id job-id edits))

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
