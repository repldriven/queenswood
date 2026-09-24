(ns com.repldriven.queenswood.api.jobs.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def JobNotFound
  {:value {:title "REJECTED"
           :type ":scheduler/job-not-found"
           :status 404
           :detail "Scheduled job not found"}})

(def RunNotFound
  {:value {:title "REJECTED"
           :type ":scheduler/run-not-found"
           :status 404
           :detail "Scheduled run not found"}})

(def PeriodicityNotAllowed
  {:value {:title "REJECTED"
           :type ":scheduler/periodicity-not-allowed"
           :status 422
           :detail "Periodicity not allowed for this job's tasks"}})

(def RunTimeNotAllowed
  {:value {:title "REJECTED"
           :type ":scheduler/run-time-not-allowed"
           :status 422
           :detail "Run time is outside what the periodicity allows"}})

(def SystemJobLocked
  {:value
   {:title "REJECTED"
    :type ":scheduler/system-job-locked"
    :status 422
    :detail
    "System jobs have a fixed cadence; only the time of day is editable"}})

(def registry
  (examples-registry [#'JobNotFound #'RunNotFound #'PeriodicityNotAllowed
                      #'SystemJobLocked #'RunTimeNotAllowed]))

(def Job
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :job-id "daily-interest"
   :name "Daily interest"
   :kind :user
   :task-kinds [:accrue :capitalize]
   :periodicity :daily
   :allowed-periodicities [:daily]
   :run-time-minutes 1020
   :enabled true
   :last-run-at "2025-01-01T02:00:00Z"
   :next-run-at "2025-01-02T02:00:00Z"
   :created-at "2024-12-31T00:00:00Z"
   :updated-at "2024-12-31T00:00:00Z"})

(def JobId (:job-id Job))

(def JobList {:items [Job]})

(def JobScheduleUpdate
  {:periodicity :daily :run-time-minutes 1020 :enabled true})

(def TaskRun
  {:label "accrue"
   :status :succeeded
   :started-at "2025-01-02T02:00:00Z"
   :finished-at "2025-01-02T02:00:30Z"
   :records-processed 12480
   :records-failed 0})

(def Run
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :run-id "01940000-0000-7000-8000-000000000000"
   :job-id "daily-interest"
   :status :running
   :trigger-source :scheduled
   :started-at "2025-01-02T02:00:00Z"
   :tasks-total 2
   :tasks-completed 1
   :current-task "capitalize"
   :expected-end-at "2025-01-02T02:01:00Z"
   :tasks
   [TaskRun
    {:label "capitalize" :status :running :started-at "2025-01-02T02:00:30Z"}]})

(def RunId (:run-id Run))

(def RunList {:items [Run]})
