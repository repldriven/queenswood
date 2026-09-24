(ns com.repldriven.queenswood.api.onboarding.routes
  (:require
    [com.repldriven.queenswood.api.companies.examples :as companies.examples]
    [com.repldriven.queenswood.api.examples :as api.examples]
    [com.repldriven.queenswood.api.onboarding.examples :refer
     [CompanyNotActive CompanyNotFound]]
    [com.repldriven.queenswood.api.onboarding.handlers :as handlers]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def routes
  [["/onboarding"
    {:openapi {:tags ["Onboarding"] :security [{"bearerAuth" ["user"]}]}}
    ["/me"
     {:post
      {:summary "Create a bank for a registered company"
       :openapi {:operationId "OnboardMe"
                 :description
                 (str "Looks the company number up in the company registry and"
                      " creates a test bank on the micro tier in GBP, bound to"
                      " that company, with the caller as its owner. Returns "
                      "404 for an unknown company, and 422 for one that is not"
                      " active. The response carries the bank's client secret,"
                      " which is returned only here.")
                 :requestBody {:required true}
                 :parameters ^:replace [shared.parameters/ref-idempotency-key]}
       :interceptors [server/require-idempotency-key
                      bank-idempotency/cache-response]
       :parameters {:body [:ref "OnboardingRequest"]}
       :responses
       (shared.idempotency/with-responses
        {201
         {:description
          "The user, the created bank with its client secret, and the owner membership."
          :body [:ref "OnboardingResponse"]
          :openapi {:headers {"Location" (shared.headers/location "bank")}}}
         403 (ErrorExamples [#'api.examples/PolicyDenied])
         404 (ErrorResponse [#'CompanyNotFound])
         422 (ErrorResponse [#'CompanyNotActive])
         503 (ErrorExamples [#'companies.examples/CompanyRegistryUnavailable])})
       :handler handlers/onboard}}]]])
