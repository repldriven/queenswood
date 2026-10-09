(ns com.repldriven.queenswood.scheduler.domain
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.scheduler.interface :as scheduler]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(def ^:private day-ms (* 24 60 60 1000))

(def ^:private sample-horizon-ms
  "How far ahead a schedule's fire times are sampled to judge its gaps:
  two years and a day, so a yearly schedule shows one."
  (* 731 day-ms))

(def ^:private sample-limit 64)

(def task-cadences
  "How often each task may run: accrue exactly once a day, and the
  others no more than once a day."
  {:scheduler-task-kind-accrue :daily
   :scheduler-task-kind-capitalize :daily-or-longer
   :scheduler-task-kind-account-migration :daily-or-longer})

(defn- fire-times
  "The fire times of `schedule` after `from`, up to two years and a day
  ahead and at most `sample-limit` of them, or an anomaly where the
  schedule does not parse."
  [schedule from]
  (let [until (+ from sample-horizon-ms)]
    (loop [after from
           times []]
      (let [at (scheduler/next-fire-at schedule after)]
        (cond
         (error/anomaly? at)
         at

         (or (nil? at) (> at until) (= sample-limit (count times)))
         times

         :else
         (recur at (conj times at)))))))

(defn- cadence-allows?
  [cadence gaps]
  (case cadence
    :daily (and (seq gaps) (every? #(= day-ms %) gaps))
    :daily-or-longer (every? #(<= day-ms %) gaps)
    true))

(defn validate-schedule
  "Rejects a `schedule` that does not parse, never fires, or fires more
  or less often than one of `task-kinds` allows, judged on its next
  fire times after `now`."
  [task-kinds schedule now]
  (let [times (fire-times schedule now)]
    (cond
     (error/anomaly? times)
     (error/reject :scheduler/invalid-schedule
                   {:message "The schedule is not a Quartz cron expression"
                    :schedule schedule})

     (empty? times)
     (error/reject :scheduler/invalid-schedule
                   {:message "The schedule never fires"
                    :schedule schedule})

     :else
     (let [gaps (map - (rest times) times)
           refused (remove (fn [kind]
                             (cadence-allows? (task-cadences kind) gaps))
                           task-kinds)]
       (when (seq refused)
         (error/reject :scheduler/periodicity-not-allowed
                       {:message (str "The schedule fires more or less often"
                                      " than its tasks allow")
                        :schedule schedule
                        :task-kinds (vec refused)}))))))

(defn system?
  "True when `job` is a platform-owned (system) job."
  [job]
  (= :scheduler-job-kind-system (:kind job)))

(defn- fire-days
  "A cron's fields after its hours: the days, months and weekdays it
  fires on, whatever the time."
  [schedule]
  (drop 3 (str/split (str/trim schedule) #"\s+")))

(defn validate-system-edits
  "Rejects when a system job's `edits` pause it or change the days its
  schedule fires on: only its time of day is editable."
  [job edits]
  (let [{:keys [schedule status]} edits]
    (when (and (system? job)
               (or (some? status)
                   (and schedule
                        (not= (fire-days schedule)
                              (fire-days (:schedule job))))))
      (error/reject
       :scheduler/system-job-locked
       {:message
        "System jobs have a fixed cadence; only the time of day is editable"
        :job-id (:job-id job)}))))

(defn run-duration
  "Wall-clock duration of a succeeded run, or nil for any other."
  [run]
  (when-let [succeeded-at (:succeeded-at run)]
    (- succeeded-at (:created-at run))))

(def ^:private period-holding-statuses
  #{:scheduler-run-status-running :scheduler-run-status-succeeded})

(defn- same-slot?
  "True where `schedule` does not fire after `earlier` and by `later`,
  so a run started at each answers the same fire."
  [schedule earlier later]
  (let [next-fire (scheduler/next-fire-at schedule earlier)]
    (and (not (error/anomaly? next-fire))
         (or (nil? next-fire) (> next-fire later)))))

(defn period-refusal
  "A rejection when one of `runs` of `job` answers the same fire of its
  schedule as a run starting at `now`, and is running or succeeded,
  else nil. A failed run leaves its slot open to another."
  [job runs now]
  (let [{:keys [job-id schedule]} job
        holding (first (filter (fn [{:keys [status created-at]}]
                                 (and (contains? period-holding-statuses status)
                                      (<= created-at now)
                                      (same-slot? schedule created-at now)))
                               runs))]
    (when holding
      (error/reject :scheduler/period-already-run
                    {:message (if (= :scheduler-run-status-running
                                     (:status holding))
                                "The job is already running this period"
                                "The job has already run this period")
                     :job-id job-id
                     :run-id (:run-id holding)}))))

(defn expected-end-at
  "`created-at` plus the previous successful run's duration, or nil
  when there is no succeeded prior run to estimate from."
  [created-at prev-run]
  (when-let [duration (run-duration prev-run)]
    (+ created-at duration)))

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
                      :processed-count (:accounts-processed result)
                      :failed-count (:accounts-failed result)))

(defn failed-task
  "Closes a task with the anomaly that stopped it, and the counts its
  payload carries where it carries them. The reason is the one the run
  carries too."
  [task finished-at anomaly]
  (let [{:keys [accounts-processed accounts-failed]} (error/payload anomaly)]
    (utility/assoc-some (assoc task
                               :status :scheduler-task-status-failed
                               :finished-at finished-at
                               :failure-reason (error/format-anomaly anomaly))
                        :processed-count accounts-processed
                        :failed-count accounts-failed)))

(defn skipped-tasks
  "The tasks after a failure, which the run never reached. Recorded
  rather than left absent so the sequence a job declared is legible
  from the run alone."
  [labels]
  (mapv (fn [label]
          {:label label :status :scheduler-task-status-skipped})
        labels))
