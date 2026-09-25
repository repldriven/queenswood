(ns com.repldriven.queenswood.api.cash-account-product.routes
  (:require
    [com.repldriven.queenswood.api.cash-account-product.handlers :as handlers]
    [com.repldriven.queenswood.api.cash-account-product.queries :as queries]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :as api-schema :refer
     [ErrorExamples ErrorResponse]]
    [com.repldriven.queenswood.cash-account-product-api.interface :as
     cash-account-product-api :refer
     [CurrencyNotAllowed DraftAlreadyExists ProductNotFound TemplateMismatch
      VersionImmutable VersionNotFound]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def routes
  [["/cash-account-product-templates"
    {:openapi {:tags ["Cash Account Products"]}}
    [""
     {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                :parameters [shared.parameters/ref-bank-id-header]}
      :get {:summary "List cash account product templates"
            :openapi {:operationId "ListCashAccountProductTemplates"
                      :description
                      (str "The instrument shapes a product can be created "
                           "from: product type, balance-sheet side, balance "
                           "buckets, allowed currencies and payment-address "
                           "schemes. Every version of a product copies them "
                           "from its template. Templates reserved for a "
                           "bank's own accounts are not listed.")}
            :responses {200 {:description "The product templates."
                             :body [:ref "CashAccountProductTemplateList"]}}
            :handler queries/list-templates}}]]
   ["/cash-account-products"
    {:openapi {:tags ["Cash Account Products"]}}
    [""
     {:get {:summary "List cash account products"
            :openapi {:operationId "ListCashAccountProducts"
                      :description
                      (str "The bank's products, each with every version it "
                           "has had, drafts and discarded versions included. "
                           "The bank's internal products, such as the one "
                           "behind its own-funds accounts, are not listed.")
                      :security [{"bearerAuth" ["org:viewer"]}]
                      :parameters ^:replace
                                  [shared.parameters/ref-page
                                   shared.parameters/ref-bank-id-header]}
            :parameters {:query shared.parameters/page-query}
            :responses {200 {:description
                             "The bank's products, each with its versions."
                             :body [:ref "CashAccountProductList"]}}
            :handler queries/list-products}
      :post {:summary "Create a cash account product"
             :openapi {:operationId "CreateCashAccountProduct"
                       :description
                       (str "Creates the product with its first version as a "
                            "draft, and returns that version. A currency the "
                            "template does not allow is refused with 422.")
                       :security [{"bearerAuth" ["org:developer"]}]
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-bank-id-header
                                    shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "CashAccountProductRequest"]}
             :responses
             (shared.idempotency/with-responses
              {201 {:description "The product's first version, as a draft."
                    :body [:ref "CashAccountProductVersion"]
                    :openapi {:headers {"Location" (shared.headers/location
                                                    "draft version")}
                              :links cash-account-product-api/from-draft}}
               403 (ErrorExamples [#'api-schema/PolicyDenied])
               422 (ErrorResponse [#'CurrencyNotAllowed])
               429 (ErrorResponse [#'api-schema/PolicyLimitExceeded])})
             :handler handlers/create-product}}]
    ["/{product-id}" {:parameters {:path {:product-id [:ref "ProductId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :get {:summary "Retrieve a cash account product"
             :openapi {:operationId "RetrieveCashAccountProduct"
                       :description
                       (str "The product with every version it has had, in any "
                            "status.")}
             :responses {200 {:description "The product with its versions."
                              :body [:ref "CashAccountProduct"]
                              :openapi {:links
                                        cash-account-product-api/from-product}}
                         404 (ErrorResponse [#'ProductNotFound])}
             :handler queries/get-product}}]
     ["/versions"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :post {:summary "Open a draft version of a product"
              :openapi {:operationId "OpenCashAccountProductDraft"
                        :description
                        (str "Starts the product's next version as a draft "
                             "with the terms in the request, none carried "
                             "over from earlier versions, and the product's "
                             "own template. A product that already has a "
                             "draft is refused with 409.")
                        :requestBody {:required true}}
              :parameters {:body [:ref "CashAccountProductDraftRequest"]}
              :responses {201 {:description "The new draft version."
                               :body [:ref "CashAccountProductVersion"]
                               :openapi
                               {:headers {"Location" (shared.headers/location
                                                      "draft version")}
                                :links cash-account-product-api/from-draft}}
                          403 (ErrorExamples [#'api-schema/PolicyDenied])
                          404 (ErrorResponse [#'ProductNotFound])
                          409 (ErrorResponse [#'DraftAlreadyExists])
                          422 (ErrorResponse [#'CurrencyNotAllowed
                                              #'TemplateMismatch])}
              :handler handlers/open-draft}}]
     ["/versions/{version-id}"
      {:parameters {:path {:version-id [:ref "VersionId"]}}}
      [""
       {:get {:summary "Retrieve a product version"
              :openapi {:operationId "RetrieveCashAccountProductVersion"
                        :description
                        (str "One version of the product, whether draft, "
                             "published or discarded.")
                        :security [{"bearerAuth" ["org:viewer"]}]
                        :parameters [shared.parameters/ref-bank-id-header]}
              :responses {200 {:description "The version."
                               :body [:ref "CashAccountProductVersion"]}
                          404 (ErrorResponse [#'VersionNotFound])}
              :handler queries/get-version}
        :put {:summary "Update a draft product version"
              :openapi {:operationId "UpdateCashAccountProductDraft"
                        :description
                        (str "Replaces the draft's terms with those in the "
                             "request. A version that is not a draft is "
                             "refused with 409.")
                        :security [{"bearerAuth" ["org:developer"]}]
                        :parameters [shared.parameters/ref-bank-id-header]
                        :requestBody {:required true}}
              :parameters {:body [:ref "CashAccountProductDraftRequest"]}
              :responses {200 {:description "The updated draft."
                               :body [:ref "CashAccountProductVersion"]
                               :openapi {:links
                                         cash-account-product-api/from-draft}}
                          403 (ErrorExamples [#'api-schema/PolicyDenied])
                          404 (ErrorResponse [#'VersionNotFound])
                          409 (ErrorResponse [#'VersionImmutable])
                          422 (ErrorResponse [#'CurrencyNotAllowed
                                              #'TemplateMismatch])}
              :handler handlers/update-draft}
        :delete {:summary "Discard a draft product version"
                 :openapi {:operationId "DiscardCashAccountProductDraft"
                           :description
                           (str "Marks the draft discarded, keeping it in the "
                                "product's history, so that another draft "
                                "can be opened. A version that is not a "
                                "draft is refused with 409.")
                           :security [{"bearerAuth" ["org:developer"]}]
                           :parameters [shared.parameters/ref-bank-id-header]}
                 :responses {204 {:description
                                  "The draft was discarded. No body."}
                             403 (ErrorExamples [#'api-schema/PolicyDenied])
                             404 (ErrorResponse [#'VersionNotFound])
                             409 (ErrorResponse [#'VersionImmutable])}
                 :handler handlers/discard-draft}}]
      ["/publish"
       {:openapi {:security [{"bearerAuth" ["org:developer"]}]
                  :parameters [shared.parameters/ref-bank-id-header]}
        :post {:summary "Publish a draft product version"
               :openapi {:operationId "PublishCashAccountProductDraft"
                         :description
                         (str "Fixes the draft's terms, which cannot change "
                              "afterwards. An account opens on the published "
                              "version whose effective window contains its "
                              "opening day, and an existing account keeps "
                              "its version until a migration moves it. A "
                              "version that is not a draft is refused with "
                              "409.")}
               :responses
               {200 {:description "The published version."
                     :body [:ref "CashAccountProductVersion"]
                     :openapi {:links cash-account-product-api/from-published}}
                403 (ErrorExamples [#'api-schema/PolicyDenied])
                404 (ErrorResponse [#'VersionNotFound])
                409 (ErrorResponse [#'VersionImmutable])}
               :handler handlers/publish-draft}}]]]]])
