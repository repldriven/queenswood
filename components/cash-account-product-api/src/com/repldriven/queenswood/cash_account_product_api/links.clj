(ns com.repldriven.queenswood.cash-account-product-api.links
  "OpenAPI 3 `links` objects that hint at the state transitions
  available from each 2xx response. Consumed by schemathesis for
  stateful test generation and rendered by Scalar as clickable
  workflow maps.

  JSON-pointer values reference the response body using the
  kebab-case key shape we emit on the wire — e.g.
  `$response.body#/product-id`.")

(def from-product
  "Links available on `GET /cash-account-products/{product-id}`."
  {"GetProduct" {:operationId "RetrieveCashAccountProduct"
                 :parameters {"product-id" "$response.body#/product-id"}}
   "OpenDraft" {:operationId "OpenCashAccountProductDraft"
                :parameters {"product-id" "$response.body#/product-id"}}})

(def from-draft
  "Links available on any response whose body is a draft-state
  `CashAccountProductVersion` (create-product, open-draft,
  update-draft)."
  {"GetVersion" {:operationId "RetrieveCashAccountProductVersion"
                 :parameters {"product-id" "$response.body#/product-id"
                              "version-id" "$response.body#/version-id"}}
   "UpdateDraft" {:operationId "UpdateCashAccountProductDraft"
                  :parameters {"product-id" "$response.body#/product-id"
                               "version-id" "$response.body#/version-id"}}
   "DiscardDraft" {:operationId "DiscardCashAccountProductDraft"
                   :parameters {"product-id" "$response.body#/product-id"
                                "version-id" "$response.body#/version-id"}}
   "Publish" {:operationId "PublishCashAccountProductDraft"
              :parameters {"product-id" "$response.body#/product-id"
                           "version-id" "$response.body#/version-id"}}})

(def from-published
  "Links available on a published-state response (publish-draft)."
  {"GetProduct" {:operationId "RetrieveCashAccountProduct"
                 :parameters {"product-id" "$response.body#/product-id"}}
   "GetVersion" {:operationId "RetrieveCashAccountProductVersion"
                 :parameters {"product-id" "$response.body#/product-id"
                              "version-id" "$response.body#/version-id"}}
   "OpenDraft" {:operationId "OpenCashAccountProductDraft"
                :parameters {"product-id" "$response.body#/product-id"}}})
