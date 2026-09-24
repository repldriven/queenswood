(ns com.repldriven.queenswood.api.policy.routes
  (:require
    [com.repldriven.queenswood.api.bank.examples :refer [ForeignBankRead]]
    [com.repldriven.queenswood.api.policy.examples :refer [PolicyNotFound]]
    [com.repldriven.queenswood.api.policy.queries :as queries]

    [com.repldriven.queenswood.api.shared.interceptors :as shared.interceptors]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorExamples ErrorResponse]]))

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
   ["/banks/{bank-id}"
    ;; A bank reads its own policies as well as an operator reads any
    ;; bank's: `own-bank` holds the tenant boundary.
    {:openapi {:tags ["Policies"]
               :security [{"bearerAuth" ["org:viewer"]}
                          {"bearerAuth" ["admin"]}]}
     :parameters {:path {:bank-id [:ref "BankId"]}}
     :interceptors [shared.interceptors/own-bank]}
    ["/policies"
     {:get {:summary "List the bank's policies"
            :openapi {:operationId "ListBankPolicies"
                      :parameters ^:replace
                                  [shared.parameters/ref-bank-id
                                   shared.parameters/ref-bank-id-header]
                      :description
                      (str "The platform tier's policies, which apply to "
                           "every bank, and those bound to this bank, each "
                           "as written rather than resolved against the "
                           "others. A member can list only their own bank's;"
                           " another is refused with 403.")}
            :responses {200 {:description
                             "The policies in effect for the bank, as written."
                             :body [:ref "PolicyList"]}
                        403 (ErrorExamples [#'ForeignBankRead])}
            :handler queries/list-bank-policies}}]
    ["/effective-policy"
     {:get {:summary "Retrieve the bank's effective policy"
            :openapi {:operationId "RetrieveEffectivePolicy"
                      :parameters ^:replace
                                  [shared.parameters/ref-bank-id
                                   shared.parameters/ref-bank-id-header]
                      :description
                      (str "The bank's policies resolved as they are "
                           "enforced: a denying capability wins over an "
                           "allowing one, and the most restrictive limit "
                           "wins. Each capability and limit names the "
                           "policy it came from. A member can retrieve only"
                           " their own bank's; another is refused with 403.")}
            :responses {200 {:description "The resolved policy."
                             :body [:ref "EffectivePolicy"]}
                        403 (ErrorExamples [#'ForeignBankRead])}
            :handler queries/get-effective-policy}}]]])
