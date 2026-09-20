(ns com.repldriven.queenswood.scheduler.domain
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.set :as set]))

(def all-periods
  #{:scheduler-periodicity-hourly :scheduler-periodicity-daily
    :scheduler-periodicity-monthly :scheduler-periodicity-yearly})

(def daily-or-longer
  #{:scheduler-periodicity-daily :scheduler-periodicity-monthly
    :scheduler-periodicity-yearly})

;; Per-task periodicity constraints. Accrual must run once per day;
;; capitalization and account-migration may run on any cadence of a
;; day or longer; the reward pass runs hourly or daily.
(def task-allowed-periods
  {:scheduler-task-kind-accrue #{:scheduler-periodicity-daily}
   :scheduler-task-kind-capitalize daily-or-longer
   :scheduler-task-kind-account-migration daily-or-longer
   :scheduler-task-kind-reward #{:scheduler-periodicity-hourly
                                 :scheduler-periodicity-daily}})

(defn job-allowed-periods
  "Periodicities a job may use — the intersection of its tasks'
  allowed periodicities. A job with no tasks allows none."
  [task-kinds]
  (if (seq task-kinds)
    (reduce (fn [acc kind]
              (set/intersection acc (task-allowed-periods kind all-periods)))
            all-periods
            task-kinds)
    #{}))

(defn periodicity-allowed?
  [task-kinds periodicity]
  (contains? (job-allowed-periods task-kinds) periodicity))

(defn validate-periodicity
  "Rejects when `periodicity` is not in the job's allowed set."
  [task-kinds periodicity]
  (when-not (periodicity-allowed? task-kinds periodicity)
    (error/reject :scheduler/periodicity-not-allowed
                  {:message "Periodicity not allowed for this job's tasks"
                   :periodicity periodicity
                   :allowed (job-allowed-periods task-kinds)})))

(defn validate-run-time
  "Rejects a run time the periodicity cannot place: an hourly job's is
  the minute past the hour, so sixty or more names no minute, and any
  other's is minutes past midnight, so a day's worth or more names no
  time."
  [periodicity run-time-minutes]
  (let [limit (if (= :scheduler-periodicity-hourly periodicity) 60 1440)]
    (when-not (and (nat-int? run-time-minutes) (< run-time-minutes limit))
      (error/reject :scheduler/run-time-not-allowed
                    {:message "Run time is outside what the periodicity allows"
                     :periodicity periodicity
                     :run-time-minutes run-time-minutes
                     :limit limit}))))

(defn monthly-day-or-default
  "Normalise a monthly-day to `:first` / `:last`, defaulting an unset or
  unknown value to first."
  [monthly-day]
  (if (= monthly-day :scheduler-monthly-day-last)
    :scheduler-monthly-day-last
    :scheduler-monthly-day-first))

(defn ->cron
  "Quartz 6-field cron expression for a periodicity firing at
  `run-time-minutes` past midnight (UTC). Hourly fires every hour at
  that many minutes past it; daily fires every day; monthly on the
  first or last day (per `monthly-day`, default first — Quartz `L` is
  the last day of the month); yearly on Jan 1. Seconds are always 0."
  ([periodicity run-time-minutes]
   (->cron periodicity run-time-minutes nil))
  ([periodicity run-time-minutes monthly-day]
   (let [h (quot run-time-minutes 60)
         m (mod run-time-minutes 60)]
     (case periodicity
       :scheduler-periodicity-hourly (format "0 %d * * * ?" m)
       :scheduler-periodicity-daily (format "0 %d %d * * ?" m h)
       :scheduler-periodicity-monthly
       (format "0 %d %d %s * ?"
               m
               h
               (if (= :scheduler-monthly-day-last
                      (monthly-day-or-default monthly-day))
                 "L"
                 "1"))
       :scheduler-periodicity-yearly (format "0 %d %d 1 1 ?" m h)))))

(defn system?
  "True when `job` is a platform-owned (system) job. Unknown / unset
  kinds count as user."
  [job]
  (= :scheduler-job-kind-system (:kind job)))

(def ^:private system-locked-edits
  "Fields a system job's fixed cadence does not allow the operator to
  change — only the time of day is editable."
  #{:periodicity :monthly-day :enabled})

(defn validate-system-edits
  "Rejects when a system job's `edits` touch a cadence-locked field."
  [job edits]
  (when (and (system? job)
             (some #(contains? edits %) system-locked-edits))
    (error/reject
     :scheduler/system-job-locked
     {:message
      "System jobs have a fixed cadence; only the time of day is editable"
      :job-id (:job-id job)})))

(defn run-duration
  "Wall-clock duration of a completed run, or nil if it lacks a
  finish."
  [run]
  (when (and (:started-at run) (:finished-at run))
    (- (:finished-at run) (:started-at run))))

(defn expected-end-at
  "`started-at` plus the previous successful run's duration, or nil
  when there is no completed prior run to estimate from."
  [started-at prev-run]
  (when-let [duration (run-duration prev-run)]
    (+ started-at duration)))

(defn started-task
  "A task the run has just reached."
  [label started-at]
  {:label label
   :status :scheduler-task-status-running
   :started-at started-at})

(defn finished-task
  "Closes a task with what its pass reported. `result` is the task's
  own return — its counts are read where it offers them and left off
  where it does not, so a task with nothing to count carries no zero it
  never meant."
  [task finished-at result]
  (utility/assoc-some (assoc task
                             :status :scheduler-task-status-succeeded
                             :finished-at finished-at)
                      :records-processed (:accounts-processed result)
                      :records-failed (:accounts-failed result)))

(defn failed-task
  "Closes a task with the anomaly that stopped it. The message is the
  same one the run carries, repeated here so a reader hovering one task
  need not correlate it with the run's own error."
  [task finished-at anomaly]
  (assoc task
         :status :scheduler-task-status-failed
         :finished-at finished-at
         :error (error/format-anomaly anomaly)))

(defn skipped-tasks
  "The tasks after a failure, which the run never reached. Recorded
  rather than left absent so the sequence a job declared is legible
  from the run alone."
  [labels]
  (mapv (fn [label]
          {:label label :status :scheduler-task-status-skipped})
        labels))
