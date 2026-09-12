(ns com.repldriven.queenswood.api.jobs.routes
  (:require
    [com.repldriven.queenswood.api.jobs.examples :refer
     [JobNotFound RunNotFound PeriodicityNotAllowed SystemJobLocked]]
    [com.repldriven.queenswood.api.jobs.handlers :as handlers]
    [com.repldriven.queenswood.api.jobs.queries :as queries]

    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private run-location-header
  {:schema {:type "string"} :description "URI of the newly-started run"})

(def routes
  [["/jobs"
    {:openapi {:tags ["Jobs"]}}
    [""
     {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
      :get {:summary "Retrieve scheduled jobs"
            :openapi {:operationId "RetrieveJobs"}
            :responses {200 {:body [:ref "JobList"]}}
            :handler queries/list-jobs}}]
    ["/{job-id}"
     {:parameters {:path {:job-id [:ref "JobId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :get {:summary "Retrieve a scheduled job"
             :openapi {:operationId "RetrieveJob"}
             :responses {200 {:body [:ref "Job"]}
                         404 (ErrorResponse [#'JobNotFound])}
             :handler queries/get-job}}]
     ["/schedule"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]}
       :put {:summary "Update a job's schedule (cadence, time, enabled)"
             :openapi {:operationId "UpdateJobSchedule"
                       :requestBody {:required true}}
             :parameters {:body [:ref "JobScheduleUpdate"]}
             :responses {200 {:body [:ref "Job"]}
                         404 (ErrorResponse [#'JobNotFound])
                         422 (ErrorResponse [#'PeriodicityNotAllowed
                                             #'SystemJobLocked])}
             :handler handlers/update-schedule}}]
     ["/runs"
      [""
       {:get {:summary "Retrieve a job's runs"
              :openapi {:operationId "RetrieveJobRuns"
                        :security [{"bearerAuth" ["org:viewer"]}]}
              :responses {200 {:body [:ref "RunList"]}
                          404 (ErrorResponse [#'JobNotFound])}
              :handler queries/list-runs}
        :post {:summary "Force-start the job now"
               :openapi {:operationId "StartJobRun"
                         :security [{"bearerAuth" ["org:developer"]}]
                         :parameters ^:replace
                                     [shared.parameters/ref-job-id
                                      shared.parameters/ref-idempotency-key]}
               :interceptors [server/require-idempotency-key
                              bank-idempotency/cache-response]
               :responses (shared.idempotency/with-responses
                           {201 {:body [:ref "Run"]
                                 :openapi {:headers {"Location"
                                                     run-location-header}}}
                            404 (ErrorResponse [#'JobNotFound])})
               :handler handlers/start-run}}]
      ["/{run-id}"
       {:parameters {:path {:run-id [:ref "RunId"]}}}
       [""
        {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
         :get {:summary "Retrieve a job run"
               :openapi {:operationId "RetrieveJobRun"}
               :responses {200 {:body [:ref "Run"]}
                           404 (ErrorResponse [#'RunNotFound])}
               :handler queries/get-run}}]]]]]])
