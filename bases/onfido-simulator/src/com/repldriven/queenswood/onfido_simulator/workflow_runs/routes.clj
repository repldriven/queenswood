(ns com.repldriven.queenswood.onfido-simulator.workflow-runs.routes
  (:require
    [com.repldriven.queenswood.onfido-simulator.workflow-runs.handlers
     :as handlers]

    [com.repldriven.queenswood.idv-simulator-page.interface :as page]))

(def ^:private Decision page/Submission)

(def routes
  [["/v3.6/workflow_runs"
    {:openapi {:tags ["Workflow runs"]}}
    [""
     {:post {:summary "Create a workflow run"
             :openapi {:operationId "CreateWorkflowRun"}
             :parameters {:body [:map
                                 [:workflow_id string?]
                                 [:applicant_id string?]
                                 [:tags {:optional true} [:vector string?]]
                                 [:customer_user_id {:optional true} string?]
                                 [:link {:optional true} map?]]}
             :responses {201 {:body map?} 422 {:body [:ref "ErrorResponse"]}}
             :handler (handlers/create-workflow-run nil)}
      :get {:summary "List workflow runs"
            :openapi {:operationId "ListWorkflowRuns"}
            :parameters {:query [:map
                                 [:tags {:optional true} string?]
                                 [:applicant_id {:optional true} string?]]}
            :responses {200 {:body [:vector map?]}}
            :handler (handlers/list-workflow-runs nil)}}]
    ["/{id}"
     {:get {:summary "Retrieve a workflow run"
            :openapi {:operationId "GetWorkflowRun"}
            :parameters {:path {:id string?}}
            :responses {200 {:body map?} 404 {:body [:ref "ErrorResponse"]}}
            :handler (handlers/get-workflow-run nil)}}]]
   ["/l/{id}"
    {:openapi {:tags ["Hosted flow"]}
     :get {:summary "The hosted page a workflow run's link opens"
           :openapi {:operationId "HostedWorkflowRun"}
           :parameters {:path {:id string?}}
           :handler (handlers/hosted-page nil)}}]
   ["/simulator/workflow-runs/{id}/decision"
    {:openapi {:tags ["Simulator"]}
     :post {:summary "Settle a run as the person would, and deliver its event"
            :openapi {:operationId "DecideSimulatedWorkflowRun"}
            :parameters {:path {:id string?} :body Decision}
            :responses {200 {:body map?}
                        404 {:body [:ref "ErrorResponse"]}
                        409 {:body [:ref "ErrorResponse"]}
                        422 {:body [:ref "ErrorResponse"]}}
            :handler (handlers/decide nil)}}]])
