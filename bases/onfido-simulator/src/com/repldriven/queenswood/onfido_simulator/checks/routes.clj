(ns com.repldriven.queenswood.onfido-simulator.checks.routes
  (:require
    [com.repldriven.queenswood.onfido-simulator.checks.handlers
     :as handlers]))

(def routes
  [["/v3.6/checks"
    {:openapi {:tags ["Checks"]}}
    [""
     {:get {:summary "List an applicant's checks"
            :openapi {:operationId "ListChecks"}
            :parameters {:query [:map [:applicant_id string?]]}
            :responses {200 {:body [:map [:checks [:vector map?]]]}}
            :handler (handlers/list-checks nil)}}]
    ["/{id}"
     {:get {:summary "Get a check"
            :openapi {:operationId "GetCheck"}
            :parameters {:path {:id string?}}
            :responses {200 {:body map?} 404 {:body [:ref "ErrorResponse"]}}
            :handler (handlers/get-check nil)}}]]
   ["/v3.6/reports"
    {:openapi {:tags ["Reports"]}
     :get {:summary "List a check's reports"
           :openapi {:operationId "ListReports"}
           :parameters {:query [:map [:check_id string?]]}
           :responses {200 {:body [:map [:reports [:vector map?]]]}}
           :handler (handlers/list-reports nil)}}]])
