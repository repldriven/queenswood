(ns com.repldriven.queenswood.api-schema.interface
  "Schema and coercion primitives shared by every API surface: the
  registry builders that turn vars into malli registries and OpenAPI
  `components` sections, the prefixed-id schema, the error-response
  shape, the enum coercion builder, the encoder that renders a body in
  the spelling a route would, the two `:unique-vector` collection
  schemas, and the registry of cross-cutting schemas — timestamps,
  dates, country and currency primitives, amounts, names, the entity
  ids and product enums more than one resource carries, and the
  `page` / `embed` query objects. Requiring this namespace registers
  the `:unique-vector` and `:unique-vector-lax` OpenAPI projections."
  (:require
    [com.repldriven.queenswood.api-schema.coercion :as coercion]
    [com.repldriven.queenswood.api-schema.components :as components]
    [com.repldriven.queenswood.api-schema.schema :as schema]))

(defn components-registry
  "Malli registry mapping each var's name to its dereferenced schema.
  Feeds both the coercion `:registry` so `[:ref \"Name\"]` resolves and
  the exported document's `components/schemas` section.

  Args:
  - vars: sequence of vars, each holding a malli schema."
  [vars]
  (schema/components-registry vars))

(defn id-schema
  "Malli :re schema for a prefixed ULID entity id of the shape
  <prefix>.<26 Crockford-base32 lowercase chars>.

  The same regex drives runtime validation and OpenAPI `pattern`
  output, so fuzzers and clients see the same constraint we enforce.

  Prefixes must be alphanumeric ASCII — they are embedded literally
  (no regex-quoting) so the resulting pattern stays portable across
  Java, Python, and JavaScript regex engines.

  Args:
  - title: schema title, as it appears in the document.
  - prefix: the id's literal prefix, alphanumeric ASCII.
  - example: an example id, for the `json-schema/example` property."
  [title prefix example]
  (schema/id-schema title prefix example))

(defn examples-registry
  "Map of example name to example value, keyed by each var's name.
  Feeds the exported document's `components/examples` section.

  Args:
  - examples: sequence of vars, each holding an OpenAPI example map."
  [examples]
  (schema/examples-registry examples))

(def
  ^{:doc
    "Malli schema for the RFC 9457 problem-details body the API
  returns on a rejection — `title`, `type`, `status`, and an optional
  `detail`. Registered as `ErrorResponse` so `[:ref \"ErrorResponse\"]`
  resolves everywhere."}
  ErrorResponseSchema
  schema/ErrorResponseSchema)

(defn ErrorResponse
  "Reitit `:responses` entry for one error status: an
  `application/json` body of `[:ref \"ErrorResponse\"]` whose `examples`
  `$ref` the named examples in `components/examples`.

  Args:
  - examples: sequence of vars, each holding an OpenAPI example map."
  [examples]
  (schema/ErrorResponse examples))

(defn enum-coercion
  "Builds decoder, encoder, and json-schema from a
  string-to-keyword mapping. When unknown-key is provided,
  the encoder maps it to :unknown.

  Returns `{:decode :encode :json-schema :enum-schema}`, where
  `:enum-schema` is a fn of no args, or of one map of extra schema
  properties, returning the `:enum` schema.

  Args:
  - m: map of wire string to internal keyword.
  - unknown-key (optional): keyword the encoder maps to `:unknown`,
    and an extra permitted value on the schema."
  ([m] (coercion/enum-coercion m))
  ([m unknown-key] (coercion/enum-coercion m unknown-key)))

(defn api-encoder
  "A function encoding a value through the `:encode/api` properties
  `schema` and everything it references carry — the same transform the
  API's response coercion applies on the way out. A surface rendering a
  body outside a route uses it to emit the bytes the route would:
  timestamps as ISO-8601, enums as their wire strings.

  Args:
  - schema: the malli schema to encode through.
  - registry: registry resolving every `[:ref \"X\"]` the schema
    reaches. Malli's own default schemas are merged in."
  [schema registry]
  (coercion/api-encoder schema registry))

(def
  ^{:doc
    "Malli collection schema `:unique-vector` — a sequential
  collection that rejects duplicate items at validation. Use it on
  request bodies, where a non-unique array should earn a 400. Projects
  to `{type: array, items: …, uniqueItems: true}`."}
  unique-vector-schema
  components/unique-vector-schema)

(def
  ^{:doc
    "Malli collection schema `:unique-vector-lax` — shape only:
  it accepts duplicates at runtime while still advertising
  `uniqueItems: true`. Use it on response bodies, where stored records
  may carry duplicates from historical writes."}
  unique-vector-lax-schema
  components/unique-vector-lax-schema)

(def
  ^{:doc
    "Malli registry of the cross-cutting schemas every API
  surface shares — timestamps, dates, country and currency primitives,
  amounts, names, the entity ids and product enums more than one
  resource carries, and the `page` / `embed` query objects. Merged into
  the coercion registry in `api.clj`, so `[:ref \"X\"]` resolves the
  same definition on every route."}
  registry
  components/registry)

(def
  ^{:doc
    "The example ids the shared `BankId`, `PartyId`, `ProductId` and
  `VersionId` schemas advertise, keyed by the schema's name. A resource
  example takes its own id from here, so the document cannot show one
  id on a schema and another on the example beside it."}
  id-examples
  components/id-examples)

(def
  ^{:doc
    "Malli schema for the client-generated idempotency key:
  16-255 characters of URL-safe ASCII, which a UUID v4 or a ULID both
  satisfy."}
  IdempotencyKey
  components/IdempotencyKey)
