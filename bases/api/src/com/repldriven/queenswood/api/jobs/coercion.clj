(ns com.repldriven.queenswood.api.jobs.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private periodicity-enum
  (coercion/enum-coercion {"daily" :scheduler-periodicity-daily
                           "monthly" :scheduler-periodicity-monthly
                           "yearly" :scheduler-periodicity-yearly}
                          :scheduler-periodicity-unknown))

(def ^:private task-kind-enum
  (coercion/enum-coercion {"accrue" :scheduler-task-kind-accrue
                           "capitalize" :scheduler-task-kind-capitalize
                           "account-migration"
                           :scheduler-task-kind-account-migration}
                          :scheduler-task-kind-unknown))

(def ^:private run-status-enum
  (coercion/enum-coercion {"running" :scheduler-run-status-running
                           "succeeded" :scheduler-run-status-succeeded
                           "failed" :scheduler-run-status-failed}
                          :scheduler-run-status-unknown))

(def ^:private trigger-source-enum
  (coercion/enum-coercion {"scheduled" :scheduler-trigger-source-scheduled
                           "forced" :scheduler-trigger-source-forced}
                          :scheduler-trigger-source-unknown))

(def ^:private task-status-enum
  (coercion/enum-coercion {"running" :scheduler-task-status-running
                           "succeeded" :scheduler-task-status-succeeded
                           "failed" :scheduler-task-status-failed
                           "skipped" :scheduler-task-status-skipped}
                          :scheduler-task-status-unknown))

(def ^:private monthly-day-enum
  (coercion/enum-coercion {"first" :scheduler-monthly-day-first
                           "last" :scheduler-monthly-day-last}
                          :scheduler-monthly-day-unknown))

(def ^:private kind-enum
  (coercion/enum-coercion {"user" :scheduler-job-kind-user
                           "system" :scheduler-job-kind-system}
                          :scheduler-job-kind-unknown))

(def periodicity-enum-schema (:enum-schema periodicity-enum))
(def task-kind-enum-schema (:enum-schema task-kind-enum))
(def run-status-enum-schema (:enum-schema run-status-enum))
(def trigger-source-enum-schema (:enum-schema trigger-source-enum))
(def task-status-enum-schema (:enum-schema task-status-enum))
(def monthly-day-enum-schema (:enum-schema monthly-day-enum))
(def kind-enum-schema (:enum-schema kind-enum))
