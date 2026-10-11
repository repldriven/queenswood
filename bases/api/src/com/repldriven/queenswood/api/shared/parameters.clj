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
  include `ref-account-id` or the like alongside the other refs."
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

(def BankIdHeader
  "`components.parameters` entry for the `Bank-Id` header, which names
  the bank an organisation operation acts on. Every operation gated by
  an organisation level lists it. A person with one active member,
  and a service credential, may leave it out."
  {:name "Bank-Id"
   :in "header"
   :required false
   :description (str "The bank the call acts on, and the only way a "
                     "request names one. Required of a person holding "
                     "more than one active member, and of an " "operator.")
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

(def VerificationSessionId
  {:name "session-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/VerificationSessionId"}})

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

(def MigrationRunId
  {:name "run-id"
   :in "path"
   :required true
   :schema {:$ref "#/components/schemas/MigrationRunId"}})

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

(def AuditKindQuery
  "`kind` query parameter on the audit log: the kinds of act to list,
  comma-separated, every kind when absent."
  {:name "kind"
   :in "query"
   :required false
   :style "form"
   :explode false
   :schema {:type "array" :items {:$ref "#/components/schemas/AuditKind"}}
   :example ["member-role-changed" "member-removed"]})

(def AccessRefQuery
  "`ref` query parameter on the access log: a request id, idempotency
  key or correlation id, which lists only the calls that carried it."
  {:name "ref"
   :in "query"
   :required false
   :schema {:type "string" :minLength 1 :maxLength 255}
   :example "01a12a36-88ef-7f53-88f1-7973bba7fbde"})

(def AccessTagQuery
  "`tag` query parameter on the access log: the OpenAPI tags whose
  operations to list, comma-separated, every one when absent."
  {:name "tag"
   :in "query"
   :required false
   :style "form"
   :explode false
   :schema {:type "array" :items {:type "string" :minLength 1 :maxLength 64}}
   :example ["Payments" "Cash Accounts"]})

(def AuditRequestIdQuery
  "`request-id` query parameter on the audit log: the API request whose
  acts to list, as its `Request-Id` answered."
  {:name "request-id"
   :in "query"
   :required false
   :schema {:type "string" :minLength 1 :maxLength 64}
   :example "01a12a36-88ef-7f53-88f1-7973bba7fbde"})

(def PerformedByQuery
  "`performed-by` query parameter on the audit log: the principals whose
  acts to list, comma-separated, a member by user id, the bank by its
  client id or an operator by its id."
  {:name "performed-by"
   :in "query"
   :required false
   :style "form"
   :explode false
   :schema {:type "array" :items {:type "string" :minLength 1 :maxLength 64}}
   :example ["usr.01kprbmgcj35ptc8npmybhh4s7"]})

(def RequestedByQuery
  "`requested-by` query parameter on the access log: the principals whose
  calls to list, comma-separated, a member by user id, the bank by its
  client id or an operator by its id."
  {:name "requested-by"
   :in "query"
   :required false
   :style "form"
   :explode false
   :schema {:type "array" :items {:type "string" :minLength 1 :maxLength 64}}
   :example ["usr.01kprbmgcj35ptc8npmybhh4s7"]})

(def FromQuery
  "`from` query parameter on a log: the instant its entries are listed
  from, inclusive, an RFC 3339 timestamp with a time and an offset."
  {:name "from"
   :in "query"
   :required false
   :schema {:type "string" :format "date-time"}
   :example "2026-10-10T14:00:00Z"})

(def ToQuery
  "`to` query parameter on a log: the instant its entries are listed
  before, exclusive, an RFC 3339 timestamp with a time and an offset,
  after `from`."
  {:name "to"
   :in "query"
   :required false
   :schema {:type "string" :format "date-time"}
   :example "2026-10-10T15:00:00Z"})

(def PerformedByKindQuery
  "`performed-by-kind` query parameter on the audit log: the kinds of
  performer to list, comma-separated, every kind when absent."
  {:name "performed-by-kind"
   :in "query"
   :required false
   :style "form"
   :explode false
   :schema {:type "array" :items {:$ref "#/components/schemas/ActorKind"}}
   :example ["member" "bank"]})

(def PartyEmbedQuery
  "`embed` query parameter for optional sub-resource embedding on the
  party detail endpoint, deepObject-styled so clients send
  `embed[legal-name]=true`."
  {:name "embed"
   :in "query"
   :required false
   :style "deepObject"
   :explode true
   :schema {:$ref "#/components/schemas/PartyEmbedQuery"}})

(def page-query
  "Malli `:query` schema of a paged list with no other query parameter:
  the optional `page` object and nothing else."
  [:map {:closed true} [:page {:optional true} [:ref "PageQuery"]]])

(def ref-idempotency-key {:$ref "#/components/parameters/IdempotencyKey"})
(def ref-page {:$ref "#/components/parameters/PageQuery"})
(def ref-embed {:$ref "#/components/parameters/EmbedQuery"})
(def ref-party-embed {:$ref "#/components/parameters/PartyEmbedQuery"})
(def ref-account-id {:$ref "#/components/parameters/AccountId"})
(def ref-bank-id-header {:$ref "#/components/parameters/BankIdHeader"})
(def ref-invitation-token {:$ref "#/components/parameters/InvitationToken"})
(def ref-invitation-id {:$ref "#/components/parameters/InvitationId"})
(def ref-party-id {:$ref "#/components/parameters/PartyId"})
(def ref-verification-session-id
  {:$ref "#/components/parameters/VerificationSessionId"})
(def ref-job-id {:$ref "#/components/parameters/JobId"})
(def ref-migration-id {:$ref "#/components/parameters/MigrationId"})
(def ref-migration-run-id {:$ref "#/components/parameters/MigrationRunId"})
(def ref-endpoint-id {:$ref "#/components/parameters/EndpointId"})
(def ref-delivery-id {:$ref "#/components/parameters/DeliveryId"})
(def ref-delivery-filter {:$ref "#/components/parameters/DeliveryFilterQuery"})
(def ref-audit-kind {:$ref "#/components/parameters/AuditKindQuery"})
(def ref-access-ref {:$ref "#/components/parameters/AccessRefQuery"})
(def ref-access-tag {:$ref "#/components/parameters/AccessTagQuery"})
(def ref-audit-request-id {:$ref "#/components/parameters/AuditRequestIdQuery"})
(def ref-performed-by {:$ref "#/components/parameters/PerformedByQuery"})
(def ref-requested-by {:$ref "#/components/parameters/RequestedByQuery"})
(def ref-from {:$ref "#/components/parameters/FromQuery"})
(def ref-to {:$ref "#/components/parameters/ToQuery"})
(def ref-performed-by-kind
  {:$ref "#/components/parameters/PerformedByKindQuery"})
(def ref-inbound-payment-status
  {:$ref "#/components/parameters/InboundPaymentStatusQuery"})

(def registry
  "Map of OpenAPI parameter component name → parameter object. Merged
  into the top-level OpenAPI `:components :parameters` in `api.clj`."
  {"IdempotencyKey" IdempotencyKey
   "PageQuery" PageQuery
   "AuditKindQuery" AuditKindQuery
   "AccessRefQuery" AccessRefQuery
   "AccessTagQuery" AccessTagQuery
   "AuditRequestIdQuery" AuditRequestIdQuery
   "PerformedByQuery" PerformedByQuery
   "RequestedByQuery" RequestedByQuery
   "FromQuery" FromQuery
   "ToQuery" ToQuery
   "PerformedByKindQuery" PerformedByKindQuery
   "EmbedQuery" EmbedQuery
   "PartyEmbedQuery" PartyEmbedQuery
   "AccountId" AccountId
   "BankIdHeader" BankIdHeader
   "InvitationToken" InvitationToken
   "InvitationId" InvitationId
   "PartyId" PartyId
   "VerificationSessionId" VerificationSessionId
   "JobId" JobId
   "MigrationId" MigrationId
   "MigrationRunId" MigrationRunId
   "EndpointId" EndpointId
   "DeliveryId" DeliveryId
   "DeliveryFilterQuery" DeliveryFilterQuery
   "InboundPaymentStatusQuery" InboundPaymentStatusQuery})
