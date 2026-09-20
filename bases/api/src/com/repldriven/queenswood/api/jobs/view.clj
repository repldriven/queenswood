(ns com.repldriven.queenswood.api.jobs.view
  (:require
    [com.repldriven.queenswood.scheduler.interface :as scheduler]))

(def ^:private periodicity-order
  [:scheduler-periodicity-hourly :scheduler-periodicity-daily
   :scheduler-periodicity-monthly :scheduler-periodicity-yearly])

(defn- sorted-allowed
  [allowed]
  (vec (filter allowed periodicity-order)))

(defn job->api
  "Present a stored job over the wire: attach the task-allowed cadence
  set, default an unknown kind to user, and surface monthly-day only for
  monthly jobs (defaulting an unset value to first)."
  [job]
  (let [monthly? (= :scheduler-periodicity-monthly (:periodicity job))]
    (cond-> (assoc job
                   :kind (if (= :scheduler-job-kind-system (:kind job))
                           :scheduler-job-kind-system
                           :scheduler-job-kind-user)
                   :allowed-periodicities
                   (sorted-allowed (scheduler/allowed-periodicities
                                    (:task-kinds job))))

            monthly?
            (assoc :monthly-day
                   (if (= :scheduler-monthly-day-last (:monthly-day job))
                     :scheduler-monthly-day-last
                     :scheduler-monthly-day-first))

            (not monthly?)
            (dissoc :monthly-day))))
