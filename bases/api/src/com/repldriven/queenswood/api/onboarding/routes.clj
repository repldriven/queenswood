(ns com.repldriven.queenswood.api.onboarding.routes
  (:require
    [com.repldriven.queenswood.api.onboarding.examples :refer
     [CompanyNotActive CompanyNotFound]]
    [com.repldriven.queenswood.api.onboarding.handlers :as handlers]

    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def routes
  [["/onboarding"
    {:openapi {:tags ["Onboarding"] :security [{"bearerAuth" ["user"]}]}}
    ["/me"
     {:post {:summary "First-sign-in onboarding for the authenticated user"
             :openapi {:operationId "OnboardMe"
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "OnboardingRequest"]}
             :responses (shared.idempotency/with-responses
                         {201 {:body [:ref "OnboardingResponse"]}
                          404 (ErrorResponse [#'CompanyNotFound])
                          422 (ErrorResponse [#'CompanyNotActive])})
             :handler handlers/onboard}}]]])
