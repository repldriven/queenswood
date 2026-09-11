(ns com.repldriven.queenswood.cash-account-api.interface
  "The cash-account resource as the banking API publishes it: the malli
  components the document's `CashAccount` and its request and response
  shapes are built from, the examples those shapes and the rejection
  bodies carry, the enum schemas that coerce an account's status and
  type between wire strings and internal keywords, the OpenAPI `links`
  a `CashAccount` response advertises, and the projection that turns a
  stored account into a response body.

  Anything published about a cash account is projected here, so a
  second surface — a webhook notification carrying the same resource —
  emits the same shape as the read routes rather than a copy of it."
  (:require
    [com.repldriven.queenswood.cash-account-api.coercion :as coercion]
    [com.repldriven.queenswood.cash-account-api.components :as components]
    [com.repldriven.queenswood.cash-account-api.examples :as examples]
    [com.repldriven.queenswood.cash-account-api.links :as links]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the cash-account schemas, keyed by the name each
  appears under in the document's `components/schemas` — `CashAccount`
  and its id, the payment-address shapes, the status and type enums,
  and the request and response bodies of every cash-account route.
  Merged into the coercion registry in `api.clj`, so `[:ref \"X\"]`
  resolves them on any route."}
  registry
  components/registry)

(defn ->body
  "Project a stored account onto the keys `CashAccount` declares, in
  the shape a read route returns. A record field the component does not
  declare — `:idempotency-key`, `:last-rotation-idempotency-key` —
  cannot reach a body through it.

  Args:
  - account: a cash account as the query brick hands it back."
  [account]
  (components/->body account))

;; ---
;; coercion
;; ---

(defn cash-account-status-enum-schema
  "The `CashAccountStatus` `:enum` schema, coercing between the wire
  strings (`opening`, `opened`, `closing`, `closed`, `suspended`) and
  the record's prefixed keywords.

  Args:
  - extra-props (optional): map of schema properties merged over the
    enum's own, for a `:json-schema/example` and the like."
  ([] (coercion/cash-account-status-enum-schema))
  ([extra-props] (coercion/cash-account-status-enum-schema extra-props)))

(defn account-type-enum-schema
  "The `AccountType` `:enum` schema, coercing between the wire strings
  (`personal`, `business`) and the record's prefixed keywords. An
  account whose stored type reads back unset encodes as `unknown`.

  Args:
  - extra-props (optional): map of schema properties merged over the
    enum's own, for a `:json-schema/example` and the like."
  ([] (coercion/account-type-enum-schema))
  ([extra-props] (coercion/account-type-enum-schema extra-props)))

;; ---
;; links
;; ---

(def
  ^{:doc
    "OpenAPI 3 `links` for any response whose body is a `CashAccount`:
  the account itself, its balances and its transactions, each keyed by
  operation id and parameterised from the response body."}
  from-account
  links/from-account)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the cash-account
  rejection bodies, feeding the document's `components/examples`
  section."}
  examples
  examples/registry)

(def
  ^{:doc
    "An opened personal current account, as `CashAccount`'s
  `json-schema/example` and as the account a bank example embeds."}
  CashAccount
  examples/CashAccount)

(def
  ^{:doc
    "RFC 9457 body for a cash account that does not exist — 404,
  `:cash-account/not-found`."}
  CashAccountNotFound
  examples/CashAccountNotFound)

(def
  ^{:doc
    "RFC 9457 body for a product with no version published today
  — 422, `:cash-account/product-not-published`."}
  ProductNotPublished
  examples/ProductNotPublished)

(def
  ^{:doc
    "RFC 9457 body for a currency the product does not allow —
  422, `:cash-account/invalid-currency`."}
  InvalidCurrency
  examples/InvalidCurrency)

(def
  ^{:doc
    "RFC 9457 body for a party that does not exist — 404,
  `:party/not-found`."}
  PartyNotFound
  examples/PartyNotFound)

(def
  ^{:doc
    "RFC 9457 body for a product that does not exist — 404,
  `:cash-account-product/product-not-found`."}
  ProductNotFound
  examples/ProductNotFound)

(def
  ^{:doc
    "RFC 9457 body for an account whose status forbids the
  transition — 409, `:cash-account/invalid-status`."}
  CashAccountInvalidStatus
  examples/CashAccountInvalidStatus)

(def
  ^{:doc
    "RFC 9457 body for closing an account with a non-zero balance
  bucket — 409, `:cash-account/non-zero-on-close`."}
  CashAccountNonZeroBalance
  examples/CashAccountNonZeroBalance)
