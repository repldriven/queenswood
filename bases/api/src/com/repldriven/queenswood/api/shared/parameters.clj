(ns com.repldriven.queenswood.api.shared.parameters
  "Reusable OpenAPI `components.parameters` entries and `$ref` maps.

  Component definitions (`IdempotencyKey`, `PageQuery`, etc.) are raw
  OpenAPI fragment maps, registered once in `api.clj` under
  `:components {:parameters parameters/registry}`.

  The `ref-*` vars are bare `{:$ref \"...\"}` maps. Compose them at the
  call site with `^:replace [ref-a ref-b ...]` on the `:openapi
  {:parameters ...}` key. The `^:replace` metadata is required: without
  it, reitit's meta-merge would also splice in auto-generated duplicates
  from the malli `:parameters :query` / `:parameters :path` schema walk.
  It must appear at the call site (not on the var) so that multiple refs
  can be combined freely.

  Path parameters share this problem: when an operation sets
  `:openapi {:parameters ^:replace [...]}`, the replacement wipes any
  path params reitit would have auto-generated from `:parameters :path`.
  Routes that need both a path param and a query/header override must
  include `ref-account-id` / `ref-bank-id` alongside the other refs."
  (:require
    [com.repldriven.queenswood.api-schema.interface :as api-schema]

    [malli.json-schema :as mjs]))

(def IdempotencyKey
  "`components.parameters` entry for the `Idempotency-Key` header.
  The JSON Schema is derived from the shared malli `IdempotencyKey`
  and inlined — reitit's openapi assembler clobbers any manually
  provided `components.schemas` with its auto-walked set, so we don't
  rely on a `$ref` to a shared component schema here."
  {:name "Idempotency-Key"
   :in "header"
   :required true
   :schema (mjs/transform api-schema/IdempotencyKey)
   :example "01jsx6k7h0abfdv8qpm2ytn3we"})

(def PageQuery
  "Cursor-paginated `page` query parameter. Uses OpenAPI 3's
  `deepObject` / `explode: true` so clients wire-serialise as
  `page[after]=x&page[size]=20`. `additionalProperties: false`
  matches the malli `[:map {:closed true} ...]` that validates the
  incoming request after the nest-bracket interceptor rewrites it.

  `size` is declared as an integer so fuzzers don't feed non-numeric
  strings through to the handler; `after`/`before` have a min length
  so blank cursors are rejected at validation rather than silently
  treated as \"no cursor\"."
  {:name "page"
   :in "query"
   :required false
   :style "deepObject"
   :explode true
   :schema {:type "object"
            :additionalProperties false
            :properties {:after {:type "string"
                                 :minLength 1
                                 :maxLength 200
                                 :description "Cursor for next page"}
                         :before {:type "string"
                                  :minLength 1
                                  :maxLength 200
                                  :description "Cursor for previous page"}
                         :size {:type "integer"
                                :minimum 1
                                :maximum 100
                                :description "Page size"}}}})

(def EmbedQuery
  "`embed` query parameter for optional sub-resource embedding on
  cash-account GET endpoints. deepObject-styled so clients send
  `embed[balances]=true&embed[transactions]=false`."
  {:name "embed"
   :in "query"
   :required false
   :style "deepObject"
   :explode true
   :schema {:type "object"
            :additionalProperties false
            :properties {:balances {:type "boolean"
                                    :description "Embed balances"}
                         :transactions {:type "boolean"
                                        :description "Embed transactions"}}}})

(def AccountId
  "`components.parameters` entry for the `account-id` path parameter.
  Schema references the auto-walked `CashAccountId` component, so no
  inlining needed (unlike `IdempotencyKey`)."
  {:name "account-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/CashAccountId"}})

(def BankId
  "`components.parameters` entry for the `bank-id` path parameter."
  {:name "bank-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/BankId"}})

(def PartyId
  {:name "party-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/PartyId"}})

(def ProductId
  {:name "product-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/ProductId"}})

(def VersionId
  {:name "version-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/VersionId"}})

(def JobId
  {:name "job-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/JobId"}})

(def MigrationId
  {:name "migration-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/MigrationId"}})

(def CheckId
  {:name "check-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/CheckId"}})

(def PaymentId
  {:name "payment-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/PaymentId"}})

(def PolicyId
  {:name "policy-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/PolicyId"}})

(def BalanceType
  {:name "balance-type"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/BalanceType"}})

(def Currency
  {:name "currency"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/Currency"}})

(def BalanceStatus
  {:name "balance-status"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/BalanceStatus"}})

(def EndpointId
  {:name "endpoint-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/WebhookEndpointId"}})

(def DeliveryId
  {:name "delivery-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/WebhookDeliveryId"}})

(def DeliveryFilterQuery
  "`filter` query parameter on an endpoint's delivery history.
  deepObject-styled so clients send
  `filter[kind]=cash-account.opened&filter[outcome]=failed`. `from` and
  `to` bound when the delivery was created."
  {:name "filter"
   :in "query"
   :required false
   :style "deepObject"
   :explode true
   :schema {:type "object"
            :additionalProperties false
            :properties {:kind {:type "string" :description "Notification kind"}
                         :outcome {:type "string"
                                   :enum ["pending" "in-flight" "delivered"
                                          "failed"]
                                   :description "Delivery outcome"}
                         :from {:type "string"
                                :format "date-time"
                                :description "Earliest delivery creation"}
                         :to {:type "string"
                              :format "date-time"
                              :description "Latest delivery creation"}}}})

(def PartyEmbedQuery
  "`embed` query parameter for optional sub-resource embedding on the
  party detail endpoint. deepObject-styled so clients send
  `embed[person-identification]=true&embed[address]=true&embed[national-identifier]=true`."
  {:name "embed"
   :in "query"
   :required false
   :style "deepObject"
   :explode true
   :schema {:type "object"
            :additionalProperties false
            :properties
            {:person-identification
             {:type "boolean"
              :description
              "Embed person identification (names, date of birth, nationality)"}
             :address {:type "boolean" :description "Embed address"}
             :national-identifier {:type "boolean"
                                   :description "Embed national identifier"}}}})

(def ref-idempotency-key {:$ref "#/components/parameters/IdempotencyKey"})
(def ref-page {:$ref "#/components/parameters/PageQuery"})
(def ref-embed {:$ref "#/components/parameters/EmbedQuery"})
(def ref-party-embed {:$ref "#/components/parameters/PartyEmbedQuery"})
(def ref-account-id {:$ref "#/components/parameters/AccountId"})
(def ref-bank-id {:$ref "#/components/parameters/BankId"})
(def ref-party-id {:$ref "#/components/parameters/PartyId"})
(def ref-job-id {:$ref "#/components/parameters/JobId"})
(def ref-migration-id {:$ref "#/components/parameters/MigrationId"})
(def ref-endpoint-id {:$ref "#/components/parameters/EndpointId"})
(def ref-delivery-id {:$ref "#/components/parameters/DeliveryId"})
(def ref-delivery-filter {:$ref "#/components/parameters/DeliveryFilterQuery"})

(def registry
  "Map of OpenAPI parameter component name → parameter object. Merged
  into the top-level OpenAPI `:components :parameters` in `api.clj`."
  {"IdempotencyKey" IdempotencyKey
   "PageQuery" PageQuery
   "EmbedQuery" EmbedQuery
   "PartyEmbedQuery" PartyEmbedQuery
   "AccountId" AccountId
   "BankId" BankId
   "PartyId" PartyId
   "JobId" JobId
   "MigrationId" MigrationId
   "ProductId" ProductId
   "VersionId" VersionId
   "CheckId" CheckId
   "PaymentId" PaymentId
   "PolicyId" PolicyId
   "BalanceType" BalanceType
   "Currency" Currency
   "BalanceStatus" BalanceStatus
   "EndpointId" EndpointId
   "DeliveryId" DeliveryId
   "DeliveryFilterQuery" DeliveryFilterQuery})
