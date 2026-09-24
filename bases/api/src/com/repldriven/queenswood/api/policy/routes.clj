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
     {:get {:summary "List policies"
            :openapi {:operationId "ListPolicies"
                      :description
                      (str "Policies of every tier, whether or not bound to "
                           "a bank. Returns at most 20.")}
            :responses {200 {:description "The policies, at most 20."
                             :body [:ref "PolicyList"]}}
            :handler queries/list-policies}}]
    ["/{policy-id}"
     {:parameters {:path {:policy-id [:ref "PolicyId"]}}}
     [""
      {:get {:summary "Retrieve a policy"
             :openapi {:operationId "RetrievePolicy"
                       :description
                       "Any policy, whether or not it is bound to a bank."}
             :responses {200 {:description "The policy." :body [:ref "Policy"]}
                         404 (ErrorResponse [#'PolicyNotFound])}
             :handler queries/get-policy}}]]]
   ["/me/policies"
    {:openapi {:tags ["Policies"]
               :security [{"bearerAuth" ["org:viewer"]}]
               :parameters [shared.parameters/ref-bank-id-header]}}
    [""
     {:get {:summary "List the policies effective for the bank"
            :openapi {:operationId "ListEffectivePolicies"
                      :description
                      (str "The platform tier's policies, which apply to "
                           "every bank, and those bound to the bank the "
                           "`Bank-Id` header names, each as written rather "
                           "than resolved against the others.")}
            :responses {200 {:description
                             "The policies in effect for the bank, as written."
                             :body [:ref "PolicyList"]}}
            :handler queries/list-effective-policies}}]]
   ["/me/effective-policies"
    {:openapi {:tags ["Policies"]
               :security [{"bearerAuth" ["org:viewer"]}]
               :parameters [shared.parameters/ref-bank-id-header]}}
    [""
     {:get {:summary "Retrieve the bank's resolved policy"
            :openapi {:operationId "RetrieveEffectivePolicies"
                      :description
                      (str "The policies effective for the bank the "
                           "`Bank-Id` header names, resolved as they are "
                           "enforced: a denying capability wins over an "
                           "allowing one, and the most restrictive limit "
                           "wins. Each capability and limit names the "
                           "policy it came from.")}
            :responses {200 {:description "The resolved policy."
                             :body [:ref "EffectivePolicy"]}}
            :handler queries/get-effective-policy}}]]])
