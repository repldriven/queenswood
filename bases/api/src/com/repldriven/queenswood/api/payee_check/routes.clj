(ns com.repldriven.queenswood.api.payee-check.routes
  (:require
    [com.repldriven.queenswood.api.payee-check.examples :refer
     [PayeeCheckNotFound]]
    [com.repldriven.queenswood.api.payee-check.handlers :as handlers]
    [com.repldriven.queenswood.api.payee-check.links :as links]
    [com.repldriven.queenswood.api.payee-check.queries :as queries]

    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private list-query-schema
  [:map {:closed true} [:page {:optional true} [:ref "PageQuery"]]])

(def routes
  [["/payee-checks"
    {:openapi {:tags ["CoP"]}}
    [""
     {:get {:summary "List payee checks"
            :openapi {:operationId "ListPayeeChecks"
                      :security [{"bearerAuth" ["org:viewer"]}]
                      :parameters ^:replace [shared.parameters/ref-page]}
            :parameters {:query list-query-schema}
            :responses {200 {:body [:ref "PayeeCheckList"]}}
            :handler queries/list-checks}
      :post {:summary "Create a payee check"
             :openapi {:operationId "CreatePayeeCheck"
                       :security [{"bearerAuth" ["org:developer"]}]
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "PayeeCheckRequest"]}
             :responses (shared.idempotency/with-responses
                         {201 {:body [:ref "PayeeCheck"]
                               :openapi {:links links/from-check}}})
             :handler handlers/create-check}}]
    ["/{check-id}"
     {:parameters {:path {:check-id [:ref "CheckId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
       :get {:summary "Retrieve a payee check"
             :openapi {:operationId "GetPayeeCheck"}
             :responses {200 {:body [:ref "PayeeCheck"]}
                         404 (ErrorResponse [#'PayeeCheckNotFound])}
             :handler queries/get-check}}]]]])
