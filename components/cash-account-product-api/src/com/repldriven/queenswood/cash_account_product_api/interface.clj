(ns com.repldriven.queenswood.cash-account-product-api.interface
  "Cash-account products as the banking API publishes them: the malli
  components a product and its versions are built from, the examples
  those bodies and the rejection bodies carry, and the OpenAPI
  `links` a product or version response advertises."
  (:require
    [com.repldriven.queenswood.cash-account-product-api.components :as
     components]
    [com.repldriven.queenswood.cash-account-product-api.examples :as examples]
    [com.repldriven.queenswood.cash-account-product-api.links :as links]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the cash-account product schemas, keyed by the
  name each appears under in the document's `components/schemas`:
  `TemplateId`, `BalanceSheetSide`, `VersionStatus`,
  `OpeningReward`, `CashAccountProductRequest`,
  `CashAccountProductDraftRequest`, `CashAccountProductVersion`,
  `CashAccountProduct`, `CashAccountProductList`,
  `CashAccountProductTemplate`, `CashAccountProductTemplateList`.
  Merged into the coercion registry in `api.clj`, so `[:ref \"X\"]`
  resolves them on any route."}
  registry
  components/registry)

;; ---
;; links
;; ---

(def ^{:doc "OpenAPI 3 `links` for any response whose body is a draft version."}
     from-draft
  links/from-draft)

(def ^{:doc "OpenAPI 3 `links` for any response whose body is a product."}
     from-product
  links/from-product)

(def
  ^{:doc
    "OpenAPI 3 `links` for any response whose body is a published
  version."}
  from-published
  links/from-published)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the cash-account product
  bodies, feeding the document's `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:cash-account-product/currency-not-
  allowed` rejection: Currency not allowed for this product-type."}
  CurrencyNotAllowed
  examples/CurrencyNotAllowed)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:cash-account-product/draft-already-
  exists` rejection: A draft already exists."}
  DraftAlreadyExists
  examples/DraftAlreadyExists)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:cash-account-product/product-not-found`
  rejection: Product not found."}
  ProductNotFound
  examples/ProductNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:cash-account-product/template-mismatch`
  rejection: Template does not match the product's template."}
  TemplateMismatch
  examples/TemplateMismatch)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:cash-account-product/version-immutable`
  rejection: Version is not a draft and cannot be modified."}
  VersionImmutable
  examples/VersionImmutable)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:cash-account-product/version-not-found`
  rejection: Version not found."}
  VersionNotFound
  examples/VersionNotFound)
