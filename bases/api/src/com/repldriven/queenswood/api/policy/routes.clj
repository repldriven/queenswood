(ns com.repldriven.queenswood.api.policy.routes
  (:require
    [com.repldriven.queenswood.api.policy.examples :refer [PolicyNotFound]]
    [com.repldriven.queenswood.api.policy.queries :as queries]

    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]))

(def routes
  [["/policies"
    {:openapi {:tags ["Policies"] :security [{"bearerAuth" ["admin"]}]}}
    [""
     {:get {:summary "List all policies"
            :openapi {:operationId "ListPolicies"}
            :responses {200 {:body [:ref "PolicyList"]}}
            :handler queries/list-policies}}]
    ["/{policy-id}"
     {:parameters {:path {:policy-id [:ref "PolicyId"]}}}
     [""
      {:get {:summary "Get a policy by id"
             :openapi {:operationId "GetPolicy"}
             :responses {200 {:body [:ref "Policy"]}
                         404 (ErrorResponse [#'PolicyNotFound])}
             :handler queries/get-policy}}]]]
   ["/me/policies"
    {:openapi {:tags ["Policies"]
               :security [{"bearerAuth" ["org:viewer"]}]
               :parameters [shared.parameters/ref-bank-id-header]}}
    [""
     {:get {:summary "List the policies effective for my bank"
            :openapi {:operationId "ListEffectivePolicies"}
            :responses {200 {:body [:ref "PolicyList"]}}
            :handler queries/list-effective-policies}}]]
   ["/me/effective-policies"
    {:openapi {:tags ["Policies"]
               :security [{"bearerAuth" ["org:viewer"]}]
               :parameters [shared.parameters/ref-bank-id-header]}}
    [""
     {:get {:summary "Resolve my effective policies into one decision set"
            :openapi {:operationId "GetEffectivePolicies"}
            :responses {200 {:body [:ref "EffectivePolicy"]}}
            :handler queries/get-effective-policy}}]]])
