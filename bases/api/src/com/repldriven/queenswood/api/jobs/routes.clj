(ns com.repldriven.queenswood.api.jobs.routes
  (:require
    [com.repldriven.queenswood.api.jobs.examples :refer
     [JobNotFound RunNotFound PeriodicityNotAllowed SystemJobLocked
      RunTimeNotAllowed]]
    [com.repldriven.queenswood.api.jobs.handlers :as handlers]
    [com.repldriven.queenswood.api.jobs.queries :as queries]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def routes
  [["/jobs"
    {:openapi {:tags ["Jobs"]}}
    [""
     {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
      :get {:summary "List scheduled jobs"
            :openapi {:operationId "ListJobs"
                      :description
                      (str "The bank's system and user jobs, a page at a "
                           "time, each with its schedule, the cadences its "
                           "tasks allow and when it next runs.")
                      :parameters ^:replace
                                  [shared.parameters/ref-page
                                   shared.parameters/ref-bank-id-header]}
            :parameters {:query shared.parameters/page-query}
            :responses {200 {:description "A page of the bank's jobs."
                             :body [:ref "JobList"]}}
            :handler queries/list-jobs}}]
    ["/{job-id}"
     {:parameters {:path {:job-id [:ref "JobId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :get {:summary "Retrieve a scheduled job"
             :openapi {:operationId "RetrieveJob"
                       :description
                       (str "The job's schedule, the cadences its tasks "
                            "allow, and when it last ran and next runs.")}
             :responses {200 {:description "The job." :body [:ref "Job"]}
                         404 (ErrorResponse [#'JobNotFound])}
             :handler queries/get-job}}]
     ["/schedule"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :put {:summary "Update a job's schedule"
             :openapi {:operationId "UpdateJobSchedule"
                       :description
                       (str "Changes the cadence, the time of day or whether "
                            "the job is enabled, keeps any field left out, "
                            "and recomputes the next run. A cadence the "
                            "job's tasks do not allow, or a change to a "
                            "system job other than its time of day, is "
                            "refused with 422.")
                       :requestBody {:required true}}
             :parameters {:body [:ref "JobScheduleUpdate"]}
             :responses {200 {:description "The job with its new schedule."
                              :body [:ref "Job"]}
                         404 (ErrorResponse [#'JobNotFound])
                         422 (ErrorResponse [#'PeriodicityNotAllowed
                                             #'SystemJobLocked
                                             #'RunTimeNotAllowed])}
             :handler handlers/update-schedule}}]
     ["/runs"
      [""
       {:get {:summary "List a job's runs"
              :openapi {:operationId "ListJobRuns"
                        :description
                        (str "The job's scheduled and forced runs, newest "
                             "first, a page at a time.")
                        :security [{"bearerAuth" ["org:viewer"]}]
                        :parameters ^:replace
                                    [shared.parameters/ref-job-id
                                     shared.parameters/ref-page
                                     shared.parameters/ref-bank-id-header]}
              :parameters {:query shared.parameters/page-query}
              :responses {200 {:description
                               "A page of the job's runs, newest first."
                               :body [:ref "RunList"]}
                          404 (ErrorResponse [#'JobNotFound])}
              :handler queries/list-runs}
        :post {:summary "Start a job run now"
               :openapi {:operationId "StartJobRun"
                         :description
                         (str "Runs the job's tasks in order, whether or not "
                              "the job is enabled, and returns the completed "
                              "run. A failing task ends the run as failed, "
                              "skips the tasks after it and is returned as "
                              "the error, and the failed run stays readable "
                              "among the job's runs.")
                         :security [{"bearerAuth" ["org:developer"]}]
                         :parameters ^:replace
                                     [shared.parameters/ref-job-id
                                      shared.parameters/ref-bank-id-header
                                      shared.parameters/ref-idempotency-key]}
               :interceptors [server/require-idempotency-key
                              bank-idempotency/cache-response]
               :responses (shared.idempotency/with-responses
                           {201 {:description "The completed run."
                                 :body [:ref "Run"]
                                 :openapi {:headers {"Location"
                                                     (shared.headers/location
                                                      "run")}}}
                            404 (ErrorResponse [#'JobNotFound])})
               :handler handlers/start-run}}]
      ["/{run-id}"
       {:parameters {:path {:run-id [:ref "RunId"]}}}
       [""
        {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                   :parameters [shared.parameters/ref-bank-id-header]}
         :get {:summary "Retrieve a job run"
               :openapi {:operationId "RetrieveJobRun"
                         :description
                         (str "One run with the status and counts of each of "
                              "its tasks. A run of another job is refused "
                              "with 404.")}
               :responses {200 {:description "The run." :body [:ref "Run"]}
                           404 (ErrorResponse [#'RunNotFound])}
               :handler queries/get-run}}]]]]]])
