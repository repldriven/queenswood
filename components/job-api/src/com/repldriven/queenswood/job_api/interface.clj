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
