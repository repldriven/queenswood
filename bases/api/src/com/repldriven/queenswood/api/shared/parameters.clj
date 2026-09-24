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
  "Cursor-paginated `page` query parameter, deepObject-styled so clients
  send `page[after]=x&page[size]=20`. Its schema is the malli `PageQuery`
  that validates the request."
  {:name "page"
   :in "query"
   :required false
   :style "deepObject"
   :explode true
   :schema {:$ref "#/components/schemas/PageQuery"}})

(def EmbedQuery
  "`embed` query parameter for optional sub-resource embedding on
  cash-account GET endpoints, deepObject-styled so clients send
  `embed[balances]=true&embed[transactions]=false`."
  {:name "embed"
   :in "query"
   :required false
   :style "deepObject"
   :explode true
   :schema {:$ref "#/components/schemas/EmbedQuery"}})

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

(def BankIdHeader
  "`components.parameters` entry for the `Bank-Id` header, which names
  the bank an organisation operation acts on. Every operation gated by
  an organisation level lists it. A person with one active membership,
  and a service credential, may leave it out."
  {:name "Bank-Id"
   :in "header"
   :required false
   :description (str "The bank the call acts on. Required of a person "
                     "holding more than one active membership.")
   :schema {:$ref "#/components/schemas/BankId"}})

(def InvitationToken
  "`components.parameters` entry for the `Invitation-Token` header: the
  token an invitation's link carries, one of the two proofs a recipient
  may present. It travels in a header so it never reaches an access
  log."
  {:name "Invitation-Token"
   :in "header"
   :required false
   :description (str "The token from the link in the invitation email. "
                     "Without it, the signed-in person's verified email "
                     "must be the invited address.")
   :schema {:type "string" :minLength 1 :maxLength 200}})

(def InvitationId
  {:name "invitation-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/InvitationId"}})

(def PartyId
  {:name "party-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/PartyId"}})

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
  "`filter` query parameter on an endpoint's delivery history,
  deepObject-styled so clients send
  `filter[kind]=cash-account.opened&filter[outcome]=failed`."
  {:name "filter"
   :in "query"
   :required false
   :style "deepObject"
   :explode true
   :schema {:$ref "#/components/schemas/WebhookDeliveryFilterQuery"}})

(def InboundPaymentStatusQuery
  "`status` query parameter on the inbound payment list: the one status
  the list returns."
  {:name "status"
   :in "query"
   :required true
   :schema {:$ref "#/components/schemas/InboundPaymentStatus"}})

(def PartyEmbedQuery
  "`embed` query parameter for optional sub-resource embedding on the
  party detail endpoint, deepObject-styled so clients send
  `embed[person-identification]=true&embed[address]=true`."
  {:name "embed"
   :in "query"
   :required false
   :style "deepObject"
   :explode true
   :schema {:$ref "#/components/schemas/PartyEmbedQuery"}})

(def ref-idempotency-key {:$ref "#/components/parameters/IdempotencyKey"})
(def ref-page {:$ref "#/components/parameters/PageQuery"})
(def ref-embed {:$ref "#/components/parameters/EmbedQuery"})
(def ref-party-embed {:$ref "#/components/parameters/PartyEmbedQuery"})
(def ref-account-id {:$ref "#/components/parameters/AccountId"})
(def ref-bank-id {:$ref "#/components/parameters/BankId"})
(def ref-bank-id-header {:$ref "#/components/parameters/BankIdHeader"})
(def ref-invitation-token {:$ref "#/components/parameters/InvitationToken"})
(def ref-invitation-id {:$ref "#/components/parameters/InvitationId"})
(def ref-party-id {:$ref "#/components/parameters/PartyId"})
(def ref-job-id {:$ref "#/components/parameters/JobId"})
(def ref-migration-id {:$ref "#/components/parameters/MigrationId"})
(def ref-endpoint-id {:$ref "#/components/parameters/EndpointId"})
(def ref-delivery-id {:$ref "#/components/parameters/DeliveryId"})
(def ref-delivery-filter {:$ref "#/components/parameters/DeliveryFilterQuery"})
(def ref-inbound-payment-status
  {:$ref "#/components/parameters/InboundPaymentStatusQuery"})

(def registry
  "Map of OpenAPI parameter component name → parameter object. Merged
  into the top-level OpenAPI `:components :parameters` in `api.clj`."
  {"IdempotencyKey" IdempotencyKey
   "PageQuery" PageQuery
   "EmbedQuery" EmbedQuery
   "PartyEmbedQuery" PartyEmbedQuery
   "AccountId" AccountId
   "BankId" BankId
   "BankIdHeader" BankIdHeader
   "InvitationToken" InvitationToken
   "InvitationId" InvitationId
   "PartyId" PartyId
   "JobId" JobId
   "MigrationId" MigrationId
   "EndpointId" EndpointId
   "DeliveryId" DeliveryId
   "DeliveryFilterQuery" DeliveryFilterQuery
   "InboundPaymentStatusQuery" InboundPaymentStatusQuery})
