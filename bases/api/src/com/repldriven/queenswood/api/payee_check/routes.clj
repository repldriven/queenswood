(ns com.repldriven.queenswood.api.payee-check.routes
  (:require
    [com.repldriven.queenswood.api.payee-check.examples :refer
     [PayeeCheckNotFound]]
    [com.repldriven.queenswood.api.payee-check.handlers :as handlers]
    [com.repldriven.queenswood.api.payee-check.links :as links]
    [com.repldriven.queenswood.api.payee-check.queries :as queries]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private list-query-schema
  [:map {:closed true} [:page {:optional true} [:ref "PageQuery"]]])

(def routes
  [["/payee-checks"
    {:openapi {:tags ["Payee Checks"]}}
    [""
     {:get {:summary "List payee checks"
            :openapi {:operationId "ListPayeeChecks"
                      :description
                      (str "The payee checks of the bank the `Bank-Id` "
                           "header names, a page at a time.")
                      :security [{"bearerAuth" ["org:viewer"]}]
                      :parameters ^:replace
                                  [shared.parameters/ref-page
                                   shared.parameters/ref-bank-id-header]}
            :parameters {:query list-query-schema}
            :responses {200 {:description "One page of the bank's payee checks."
                             :body [:ref "PayeeCheckList"]}}
            :handler queries/list-checks}
      :post
      {:summary "Create a payee check"
       :openapi {:operationId "CreatePayeeCheck"
                 :description
                 (str "Asks the payee's bank, through Confirmation of Payee, "
                      "whether the name matches the account at the sort code "
                      "and account number, and records the result: match, "
                      "close match, no match, or unavailable when the check "
                      "could not be made. The result may carry the name the "
                      "account is held in. The check records an expiry 24 "
                      "hours after it is made.")
                 :security [{"bearerAuth" ["org:developer"]}]
                 :requestBody {:required true}
                 :parameters ^:replace
                             [shared.parameters/ref-bank-id-header
                              shared.parameters/ref-idempotency-key]}
       :interceptors [server/require-idempotency-key
                      bank-idempotency/cache-response]
       :parameters {:body [:ref "PayeeCheckRequest"]}
       :responses (shared.idempotency/with-responses
                   {201 {:description "The payee check and its result."
                         :body [:ref "PayeeCheck"]
                         :openapi {:headers {"Location" (shared.headers/location
                                                         "payee check")}
                                   :links links/from-check}}})
       :handler handlers/create-check}}]
    ["/{check-id}"
     {:parameters {:path {:check-id [:ref "CheckId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :get {:summary "Retrieve a payee check"
             :openapi {:operationId "RetrievePayeeCheck"
                       :description
                       (str "The check as it was made: the name and account "
                            "asked about, and the result.")}
             :responses {200 {:description "The payee check."
                              :body [:ref "PayeeCheck"]}
                         404 (ErrorResponse [#'PayeeCheckNotFound])}
             :handler queries/get-check}}]]]])
