(ns com.repldriven.queenswood.api.companies.routes
  (:require
    [com.repldriven.queenswood.api.companies.examples :refer [CompanyNotFound]]
    [com.repldriven.queenswood.api.companies.queries :as queries]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]))

(def routes
  [["/companies"
    {:openapi {:tags ["Onboarding"] :security [{"bearerAuth" ["user"]}]}}
    ["/{company-number}"
     {:parameters {:path {:company-number string?}}}
     [""
      {:get {:summary "Look up a company in the registry of record"
             :openapi {:operationId "LookupCompany"}
             :responses {200 {:body [:ref "Company"]}
                         404 (ErrorResponse [#'CompanyNotFound])}
             :handler queries/lookup-company}}]]]])
