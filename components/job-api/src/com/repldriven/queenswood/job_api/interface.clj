(ns com.repldriven.queenswood.job-api.interface
  "Scheduled jobs and their runs as the banking API publishes them:
  the malli components and the examples their bodies and the
  rejection bodies carry."
  (:require
    [com.repldriven.queenswood.job-api.components :as components]
    [com.repldriven.queenswood.job-api.examples :as examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the scheduled job schemas, keyed by the name
  each appears under in the document's `components/schemas`:
  `JobId`, `RunId`, `Periodicity`, `JobTaskKind`, `RunStatus`,
  `TriggerSource`, `TaskStatus`, `MonthlyDay`, `JobKind`, `Job`,
  `JobList`, `TaskRun`, `Run`, `RunList`, `JobScheduleUpdate`.
  Merged into the coercion registry in `api.clj`, so `[:ref \"X\"]`
  resolves them on any route."}
  registry
  components/registry)

;; ---
;; bodies
;; ---

(defn ->job-body
  "Project a job, its schedule already presented as a cadence, onto the
  keys `Job` declares, so a field the record holds and the API does not
  publish never reaches a body.

  Args:
  - job: a scheduler job with its cadence and allowed periodicities."
  [job]
  (components/->job-body job))

(defn ->run-body
  "Project a run onto the keys `Run` declares, and each of its tasks onto
  the keys `TaskRun` declares.

  Args:
  - run: a scheduler run with its task counts."
  [run]
  (components/->run-body run))

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the scheduled job bodies,
  feeding the document's `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:scheduler/job-not-found` rejection:
  Scheduled job not found."}
  JobNotFound
  examples/JobNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:scheduler/periodicity-not-allowed`
  rejection: Periodicity not allowed for this job's tasks."}
  PeriodicityNotAllowed
  examples/PeriodicityNotAllowed)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:scheduler/period-already-run` rejection:
  the job is running in the period a run would start in, or has run in
  it and pays interest, which is owed once a period."}
  PeriodAlreadyRun
  examples/PeriodAlreadyRun)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:scheduler/run-not-found` rejection:
  Scheduled run not found."}
  RunNotFound
  examples/RunNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:scheduler/run-time-not-allowed`
  rejection: Run time is outside what the periodicity allows."}
  RunTimeNotAllowed
  examples/RunTimeNotAllowed)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:scheduler/system-job-locked` rejection:
  System jobs have a fixed cadence; only the time of day is
  editable."}
  SystemJobLocked
  examples/SystemJobLocked)
