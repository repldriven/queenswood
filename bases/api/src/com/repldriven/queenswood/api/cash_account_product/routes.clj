(ns com.repldriven.queenswood.api.cash-account-product.routes
  (:require
    [com.repldriven.queenswood.api.cash-account-product.handlers :as handlers]
    [com.repldriven.queenswood.api.cash-account-product.examples :refer
     [ProductNotFound VersionNotFound DraftAlreadyExists VersionImmutable
      CurrencyNotAllowed TemplateMismatch]]
    [com.repldriven.queenswood.api.cash-account-product.links :as links]
    [com.repldriven.queenswood.api.cash-account-product.queries :as queries]

    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private list-products-query-schema
  [:map {:closed true} [:page {:optional true} [:ref "PageQuery"]]])

(def ^:private location-header
  {:schema {:type "string"}
   :description "URI of the newly-created draft version"})

(def routes
  [["/cash-account-product-templates"
    {:openapi {:tags ["Cash Account Products"]}}
    [""
     {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                :parameters [shared.parameters/ref-bank-id-header]}
      :get {:summary (str "List per-product-type templates (the menu of "
                          "balance-sheet-side, balance-products, "
                          "allowed-currencies, and "
                          "allowed-payment-address-schemes that the API "
                          "applies on cash-account-product creation)")
            :openapi {:operationId "ListCashAccountProductTemplates"}
            :responses {200 {:body [:ref "CashAccountProductTemplateList"]}}
            :handler queries/list-templates}}]]
   ["/cash-account-products"
    {:openapi {:tags ["Cash Account Products"]}}
    [""
     {:get {:summary "List products with their version histories inline"
            :openapi {:operationId "ListCashAccountProducts"
                      :security [{"bearerAuth" ["org:viewer"]}]
                      :parameters ^:replace
                                  [shared.parameters/ref-page
                                   shared.parameters/ref-bank-id-header]}
            :parameters {:query list-products-query-schema}
            :responses {200 {:body [:ref "CashAccountProductList"]}}
            :handler queries/list-products}
      :post {:summary "Create a product (returns its initial draft version)"
             :openapi {:operationId "CreateCashAccountProduct"
                       :security [{"bearerAuth" ["org:developer"]}]
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-bank-id-header
                                    shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "CashAccountProductRequest"]}
             :responses (shared.idempotency/with-responses
                         {201 {:body [:ref "CashAccountProductVersion"]
                               :openapi {:headers {"Location" location-header}
                                         :links links/from-draft}}
                          422 (ErrorResponse [#'CurrencyNotAllowed])})
             :handler handlers/create-product}}]
    ["/{product-id}" {:parameters {:path {:product-id [:ref "ProductId"]}}}
     [""
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :get {:summary "Retrieve a product with its version history inline"
             :openapi {:operationId "RetrieveCashAccountProduct"}
             :responses {200 {:body [:ref "CashAccountProduct"]
                              :openapi {:links links/from-product}}
                         404 (ErrorResponse [#'ProductNotFound])}
             :handler queries/get-product}}]
     ["/versions"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :post {:summary "Open a new draft version (requires no existing draft)"
              :openapi {:operationId "OpenCashAccountProductDraft"
                        :requestBody {:required true}}
              :parameters {:body [:ref "CashAccountProductDraftRequest"]}
              :responses {201 {:body [:ref "CashAccountProductVersion"]
                               :openapi {:headers {"Location" location-header}
                                         :links links/from-draft}}
                          404 (ErrorResponse [#'ProductNotFound])
                          409 (ErrorResponse [#'DraftAlreadyExists])
                          422 (ErrorResponse [#'CurrencyNotAllowed
                                              #'TemplateMismatch])}
              :handler handlers/open-draft}}]
     ["/versions/{version-id}"
      {:parameters {:path {:version-id [:ref "VersionId"]}}}
      [""
       {:get {:summary "Retrieve a specific version"
              :openapi {:operationId "RetrieveCashAccountProductVersion"
                        :security [{"bearerAuth" ["org:viewer"]}]
                        :parameters [shared.parameters/ref-bank-id-header]
                        :headers {"ETag" {:schema {:type "string"}}
                                  "Cache-Control" {:schema {:type "string"}}}}
              :responses {200 {:body [:ref "CashAccountProductVersion"]}
                          404 (ErrorResponse [#'VersionNotFound])}
              :handler queries/get-version}
        :put {:summary "Update the draft version (draft state only)"
              :openapi {:operationId "UpdateCashAccountProductDraft"
                        :security [{"bearerAuth" ["org:developer"]}]
                        :parameters [shared.parameters/ref-bank-id-header]
                        :requestBody {:required true}}
              :parameters {:body [:ref "CashAccountProductDraftRequest"]}
              :responses {200 {:body [:ref "CashAccountProductVersion"]
                               :openapi {:links links/from-draft}}
                          404 (ErrorResponse [#'VersionNotFound])
                          409 (ErrorResponse [#'VersionImmutable])
                          422 (ErrorResponse [#'CurrencyNotAllowed
                                              #'TemplateMismatch])}
              :handler handlers/update-draft}
        :delete {:summary "Discard the draft version (draft state only)"
                 :openapi {:operationId "DiscardCashAccountProductDraft"
                           :security [{"bearerAuth" ["org:developer"]}]
                           :parameters [shared.parameters/ref-bank-id-header]}
                 :responses {204 {}
                             404 (ErrorResponse [#'VersionNotFound])
                             409 (ErrorResponse [#'VersionImmutable])}
                 :handler handlers/discard-draft}}]
      ["/publish"
       {:openapi {:security [{"bearerAuth" ["org:developer"]}]
                  :parameters [shared.parameters/ref-bank-id-header]}
        :post {:summary "Publish the draft version (draft state only)"
               :openapi {:operationId "PublishCashAccountProductDraft"}
               :responses {200 {:body [:ref "CashAccountProductVersion"]
                                :openapi {:links links/from-published}}
                           404 (ErrorResponse [#'VersionNotFound])
                           409 (ErrorResponse [#'VersionImmutable])}
               :handler handlers/publish-draft}}]]]]])
