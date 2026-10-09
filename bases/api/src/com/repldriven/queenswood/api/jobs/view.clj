(ns com.repldriven.queenswood.api.jobs.view
  (:require
    [com.repldriven.queenswood.scheduler.interface :as scheduler]

    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private periodicity-order
  [:scheduler-periodicity-hourly :scheduler-periodicity-daily
   :scheduler-periodicity-monthly :scheduler-periodicity-yearly])

(defn- sorted-allowed
  [allowed]
  (vec (filter allowed periodicity-order)))

(defn job->api
  "Present a stored job over the wire: attach the task-allowed cadence
  set, show its status as `enabled` and its run time in minutes, and
  surface monthly-day only for monthly jobs (defaulting an unset value
  to first)."
  [job]
  (let [{:keys [periodicity status run-time-mins created-at updated-at]} job
        monthly? (= :scheduler-periodicity-monthly periodicity)]
    (cond-> (-> job
                (dissoc :status :run-time-mins :updated-by)
                (assoc :enabled (= :scheduler-job-status-active status)
                       :run-time-minutes run-time-mins
                       :updated-at (or updated-at created-at)
                       :allowed-periodicities
                       (sorted-allowed (scheduler/allowed-periodicities
                                        (:task-kinds job)))))

            monthly?
            (assoc :monthly-day
                   (if (= :scheduler-monthly-day-last (:monthly-day job))
                     :scheduler-monthly-day-last
                     :scheduler-monthly-day-first))

            (not monthly?)
            (dissoc :monthly-day))))

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
