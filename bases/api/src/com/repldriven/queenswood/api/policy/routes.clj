(ns com.repldriven.queenswood.api.policy.routes
  (:require
    [com.repldriven.queenswood.api.policy.queries :as queries]

    [com.repldriven.queenswood.api.shared.interceptors :as shared.interceptors]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.bank-api.interface :refer [BankUnnamed]]
    [com.repldriven.queenswood.policy-api.interface :refer [PolicyNotFound]]))

(def routes
  [["/policies"
    {:openapi {:tags ["Policies"] :security [{"bearerAuth" ["admin"]}]}}
    [""
     {:get {:summary "List policies"
            :openapi {:operationId "ListPolicies"
                      :description
                      (str "Policies of every tier, whether or not bound to "
                           "a bank, newest first, a page at a time.")
                      :parameters ^:replace [shared.parameters/ref-page]}
            :parameters {:query shared.parameters/page-query}
            :responses {200 {:description "A page of policies."
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
   ["/bank"
    ;; The bank the `Bank-Id` header names: a member's own, or any an
    ;; operator names. `named-bank` refuses an operator who names none.
    {:openapi {:tags ["Policies"]
               :security [{"bearerAuth" ["org:viewer"]}
                          {"bearerAuth" ["admin"]}]
               :parameters [shared.parameters/ref-bank-id-header]}
     :interceptors [shared.interceptors/named-bank]}
    ["/policies"
     {:get {:summary "List the bank's policies"
            :openapi {:operationId "ListBankPolicies"
                      :description
                      (str "The platform tier's policies, which apply to "
                           "every bank, and those bound to the bank the "
                           "`Bank-Id` header names, each as written rather "
                           "than resolved against the others.")}
            :responses {200 {:description
                             "The policies in effect for the bank, as written."
                             :body [:ref "PolicyList"]}
                        403 (ErrorExamples [#'BankUnnamed])}
            :handler queries/list-bank-policies}}]
    ["/effective-policy"
     {:get {:summary "Retrieve the bank's effective policy"
            :openapi {:operationId "RetrieveEffectivePolicy"
                      :description
                      (str "The policies of the bank the `Bank-Id` header "
                           "names, resolved as they are enforced: a denying "
                           "capability wins over an allowing one, and the "
                           "most restrictive limit wins. Each capability and"
                           " limit names the policy it came from.")}
            :responses {200 {:description "The resolved policy."
                             :body [:ref "EffectivePolicy"]}
                        403 (ErrorExamples [#'BankUnnamed])}
            :handler queries/get-effective-policy}}]]])
