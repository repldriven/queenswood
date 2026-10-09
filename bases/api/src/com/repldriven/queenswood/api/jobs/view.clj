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
  monthly job's day of the month and a time of day, and the cadences its
  tasks allow."
  [job]
  (let [{:keys [schedule task-kinds]} job]
    (-> job
        (dissoc :schedule :updated-by)
        (merge (schedule->cadence schedule))
        (assoc :allowed-periodicities
               (allowed-cadences task-kinds
                                 (utility/now))))))

(defn api->edits
  "A schedule edit as the API sends it, in the job's own terms: the
  cadence, day of the month and time of day as a schedule, built over
  what `job` has where the edit names none, and the status as given."
  [job body]
  (let [{:keys [periodicity monthly-day run-time-minutes status]} body
        cadence (merge (schedule->cadence (:schedule job))
                       (utility/assoc-some {}
                                           :periodicity periodicity
                                           :monthly-day monthly-day
                                           :run-time-minutes
                                           run-time-minutes))]
    (cond-> {}
            (or periodicity monthly-day run-time-minutes)
            (assoc :schedule (cadence->schedule cadence))

            status
            (assoc :status status))))

(defn run->api
  "Present a stored run over the wire, with how many tasks completed and
  which one is running read off its tasks."
  [run]
  (let [{:keys [tasks]} run
        completed (filter (fn [task]
                            (= :scheduler-task-status-completed (:status task)))
                          tasks)
        current (some (fn [task]
                        (when (contains? #{:scheduler-task-status-running
                                           :scheduler-task-status-failed}
                                         (:status task))
                          (:label task)))
                      tasks)]
    (utility/assoc-some (-> run
                            (dissoc :created-by :updated-at)
                            (assoc :tasks-completed (count completed)))
                        :current-task
                        current)))
