(ns com.repldriven.queenswood.api.jobs.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def JobNotFound
  {:value {:title "REJECTED"
           :type "scheduler/job-not-found"
           :status 404
           :detail "Scheduled job not found"}})

(def RunNotFound
  {:value {:title "REJECTED"
           :type "scheduler/run-not-found"
           :status 404
           :detail "Scheduled run not found"}})

(def PeriodicityNotAllowed
  {:value {:title "REJECTED"
           :type "scheduler/periodicity-not-allowed"
           :status 422
           :detail "Periodicity not allowed for this job's tasks"}})

(def SystemJobLocked
  {:value
   {:title "REJECTED"
    :type "scheduler/system-job-locked"
    :status 422
    :detail
    "System jobs have a fixed cadence; only the time of day is editable"}})

(def registry
  (examples-registry [#'JobNotFound #'RunNotFound #'PeriodicityNotAllowed
                      #'SystemJobLocked]))

(def Job
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :job-id "daily-interest"
   :name "Daily interest"
   :kind :scheduler-job-kind-user
   :task-kinds [:scheduler-task-kind-accrue :scheduler-task-kind-capitalize]
   :periodicity :scheduler-periodicity-daily
   :allowed-periodicities [:scheduler-periodicity-daily]
   :run-time-minutes 1020
   :enabled true
   :last-run-at 1735696800000
   :next-run-at 1735783200000
   :created-at 1735603200000
   :updated-at 1735603200000})

(def JobId (:job-id Job))

(def JobList {:jobs [Job]})

(def JobScheduleUpdate
  {:periodicity :scheduler-periodicity-daily
   :run-time-minutes 1020
   :enabled true})

(def TaskRun
  {:label "accrue"
   :status :scheduler-task-status-succeeded
   :started-at 1735783200000
   :finished-at 1735783230000
   :records-processed 12480
   :records-failed 0})

(def Run
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :run-id "01940000-0000-7000-8000-000000000000"
   :job-id "daily-interest"
   :status :scheduler-run-status-running
   :trigger-source :scheduler-trigger-source-scheduled
   :started-at 1735783200000
   :tasks-total 2
   :tasks-completed 1
   :current-task "capitalize"
   :expected-end-at 1735783260000
   :tasks [TaskRun
           {:label "capitalize"
            :status :scheduler-task-status-running
            :started-at 1735783230000}]})

(def RunId (:run-id Run))

(def RunList {:runs [Run]})
