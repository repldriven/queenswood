(ns com.repldriven.queenswood.api.jobs.view
  (:require
    [com.repldriven.queenswood.scheduler.interface :as scheduler]

    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]))

(def ^:private periodicity-order
  [:scheduler-periodicity-hourly :scheduler-periodicity-daily
   :scheduler-periodicity-monthly :scheduler-periodicity-yearly])

(defn- schedule->cadence
  "The cadence, day of the month and time of day the API shows for one of
  the schedules `cadence->schedule` builds."
  [schedule]
  (let [[_ m h day month] (str/split (str/trim schedule) #"\s+")
        minute (parse-long m)
        time-of-day (if (= "*" h) minute (+ (* 60 (parse-long h)) minute))]
    (merge {:run-time-minutes time-of-day}
           (cond
            (= "*" h)
            {:periodicity :scheduler-periodicity-hourly}

            (= ["*" "*"] [day month])
            {:periodicity :scheduler-periodicity-daily}

            (= "*" month)
            {:periodicity :scheduler-periodicity-monthly
             :monthly-day (if (= "L" day)
                            :scheduler-monthly-day-last
                            :scheduler-monthly-day-first)}

            :else
            {:periodicity :scheduler-periodicity-yearly}))))

(defn- cadence->schedule
  "The Quartz cron for a cadence, day of the month and time of day as the
  API states them."
  [{:keys [periodicity monthly-day run-time-minutes]}]
  (let [h (quot run-time-minutes 60)
        m (mod run-time-minutes 60)]
    (case periodicity
      :scheduler-periodicity-hourly (format "0 %d * * * ?" m)
      :scheduler-periodicity-daily (format "0 %d %d * * ?" m h)
      :scheduler-periodicity-monthly
      (format "0 %d %d %s * ?"
              m
              h
              (if (= :scheduler-monthly-day-last monthly-day) "L" "1"))
      :scheduler-periodicity-yearly (format "0 %d %d 1 1 ?" m h))))

(defn- allowed-cadences
  "The cadences the API offers that `task-kinds` allow, judged as
  schedules at midnight."
  [task-kinds now]
  (filterv (fn [periodicity]
             (nil? (scheduler/validate-schedule
                    task-kinds
                    (cadence->schedule {:periodicity periodicity
                                        :run-time-minutes 0})
                    now)))
           periodicity-order))

(defn job->api
  "Present a stored job over the wire: its schedule as a cadence, a
  monthly job's day of the month and a time of day, the cadences its
  tasks allow, and its status as `enabled`."
  [job]
  (let [{:keys [schedule status task-kinds created-at updated-at]} job]
    (-> job
        (dissoc :schedule :status :updated-by)
        (merge (schedule->cadence schedule))
        (assoc :enabled (= :scheduler-job-status-active status)
               :updated-at (or updated-at created-at)
               :allowed-periodicities (allowed-cadences task-kinds
                                                        (utility/now))))))

(defn api->edits
  "A schedule edit as the API sends it, in the job's own terms: the
  cadence, day of the month and time of day as a schedule, built over
  what `job` has where the edit names none, and `enabled` as the
  status."
  [job body]
  (let [{:keys [periodicity monthly-day run-time-minutes enabled]} body
        cadence (merge (schedule->cadence (:schedule job))
                       (utility/assoc-some {}
                                           :periodicity periodicity
                                           :monthly-day monthly-day
                                           :run-time-minutes
                                           run-time-minutes))]
    (cond-> {}
            (or periodicity monthly-day run-time-minutes)
            (assoc :schedule (cadence->schedule cadence))

            (some? enabled)
            (assoc :status
                   (if enabled
                     :scheduler-job-status-active
                     :scheduler-job-status-paused)))))

(defn- task->api
  [task]
  (let [{:keys [processed-count failed-count failure-reason]} task]
    (utility/assoc-some (dissoc task
                         :processed-count
                         :failed-count
                         :failure-reason)
                        :records-processed processed-count
                        :records-failed failed-count
                        :error failure-reason)))

(defn run->api
  "Present a stored run over the wire: its creation as `started-at`, its
  outcome's time as `finished-at`, how many tasks succeeded and which
  one is running read off its tasks, and its failure reason as `error`."
  [run]
  (let [{:keys [created-at succeeded-at failed-at failure-reason tasks]} run
        succeeded (filter (fn [task]
                            (= :scheduler-task-status-succeeded (:status task)))
                          tasks)
        current (some (fn [task]
                        (when (contains? #{:scheduler-task-status-running
                                           :scheduler-task-status-failed}
                                         (:status task))
                          (:label task)))
                      tasks)]
    (utility/assoc-some (-> run
                            (dissoc :created-at
                                    :created-by
                                    :updated-at
                                    :succeeded-at
                                    :failed-at
                                    :failure-reason)
                            (assoc :started-at created-at
                                   :tasks-completed (count succeeded)
                                   :tasks (mapv task->api tasks)))
                        :finished-at (or succeeded-at failed-at)
                        :current-task current
                        :error failure-reason)))
