(ns com.repldriven.queenswood.api.jobs.handlers
  (:require
    [com.repldriven.queenswood.api.jobs.view :as view]

    [com.repldriven.queenswood.api.errors :as errors]
    [com.repldriven.queenswood.api.shared.actor :as shared.actor]

    [com.repldriven.queenswood.scheduler.interface :as scheduler]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn start-run
  "Force-start the job now (POST to its runs collection). Runs the task
  pipeline synchronously and returns the completed run. A task-level
  failure records a failed run and surfaces as the task's rejection;
  the run is still retrievable via the runs endpoint."
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [job-id]} (:path parameters)
        config {:record-db record-db :record-store record-store}
        result (scheduler/force-start config
                                      bank-id
                                      job-id
                                      (shared.actor/actor auth))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 201
       :headers {"Location" (str "/v1/jobs/" job-id "/runs/" (:run-id result))}
       :body (view/run->api result)})))

(defn update-schedule
  "Edit a job's schedule — any of `:periodicity`, `:run-time-minutes`,
  `:enabled` (omitted fields keep their current value). Toggling
  `:enabled` is the pause/resume control. Persists and recomputes
  next-run; rejects a schedule the job's tasks don't allow."
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path body]} parameters
        {:keys [job-id]} path
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [job (scheduler/get-job config bank-id job-id)
                  _ (when (nil? job)
                      (error/reject :scheduler/job-not-found
                                    {:message "Scheduled job not found"
                                     :job-id job-id}))]
                 (scheduler/update-schedule config
                                            bank-id
                                            job-id
                                            (view/api->edits job body)
                                            (shared.actor/actor auth)))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body (view/job->api result)})))
