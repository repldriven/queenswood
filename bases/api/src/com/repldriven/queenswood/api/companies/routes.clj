(ns com.repldriven.queenswood.api.companies.routes
  (:require
    [com.repldriven.queenswood.api.companies.examples :refer
     [CompanyNotFound CompanyRegistryUnavailable]]
    [com.repldriven.queenswood.api.companies.queries :as queries]

    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorExamples ErrorResponse]]))

(def routes
  [["/companies"
    {:openapi {:tags ["Companies"] :security [{"bearerAuth" ["user"]}]}}
    ["/{company-number}"
     {:parameters {:path {:company-number string?}}}
     [""
      {:get
       {:summary "Retrieve a company from the registry of record"
        :openapi {:operationId "RetrieveCompany"
                  :description
                  (str "The company's name, status, type, date of creation and "
                       "registered office, as the company registry holds them. "
                       "Returns 404 for an unknown company number, and 503 when"
                       " the registry is unavailable.")}
        :responses {200 {:description "The company." :body [:ref "Company"]}
                    404 (ErrorResponse [#'CompanyNotFound])
                    503 (ErrorExamples [#'CompanyRegistryUnavailable])}
        :handler queries/lookup-company}}]]]])
