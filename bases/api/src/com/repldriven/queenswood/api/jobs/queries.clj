(ns com.repldriven.queenswood.api.jobs.queries
  (:require
    [com.repldriven.queenswood.api.jobs.view :as view]

    [com.repldriven.queenswood.api.cursor :as cursor]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.scheduler.interface :as scheduler]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- job-not-found
  [job-id]
  (error/reject :scheduler/job-not-found
                {:message "Scheduled job not found" :job-id job-id}))

(defn list-jobs
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [page]} (:query parameters)
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [{:keys [jobs] :as found}
                  (scheduler/list-jobs config bank-id (cursor/page-opts page))]
                 (cursor/page-body "/v1/jobs"
                                   page
                                   (mapv view/job->api jobs)
                                   found))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn get-job
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [job-id]} (:path parameters)
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [job (scheduler/get-job config bank-id job-id)
                  _ (when (nil? job) (job-not-found job-id))]
                 (view/job->api job))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn list-runs
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [path query]} parameters
        {:keys [job-id]} path
        {:keys [page]} query
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [job (scheduler/get-job config bank-id job-id)
                  _ (when (nil? job) (job-not-found job-id))
                  runs (scheduler/list-runs config bank-id job-id)
                  windowed (cursor/window runs :run-id :desc page)]
                 (cursor/page-body (str "/v1/jobs/" job-id "/runs")
                                   page
                                   (:page windowed)
                                   windowed))]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))

(defn get-run
  [request]
  (let [{:keys [record-db record-store auth parameters]} request
        {:keys [bank-id]} auth
        {:keys [job-id run-id]} (:path parameters)
        config {:record-db record-db :record-store record-store}
        result (let-nom>
                 [run (scheduler/get-run config bank-id run-id)
                  _ (when (or (nil? run) (not= job-id (:job-id run)))
                      (error/reject :scheduler/run-not-found
                                    {:message "Scheduled run not found"
                                     :job-id job-id
                                     :run-id run-id}))]
                 run)]
    (if (error/anomaly? result)
      (errors/anomaly->response result)
      {:status 200 :body result})))
