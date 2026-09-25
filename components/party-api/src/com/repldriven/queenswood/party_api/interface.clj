(ns com.repldriven.queenswood.party-api.interface
  "The party resource as the banking API publishes it: the malli
  components `Party` and its request and response shapes are built
  from, the examples those shapes and the rejection bodies carry,
  the OpenAPI `links` a `Party` response advertises, and the two
  projections that turn a stored party into a response body — one in
  the record's own spelling, one in the wire's.

  Anything published about a party is projected here, so a webhook
  notification carrying one emits the same shape as the read routes
  rather than a copy of it."
  (:require
    [com.repldriven.queenswood.party-api.coercion :as coercion]
    [com.repldriven.queenswood.party-api.components :as components]
    [com.repldriven.queenswood.party-api.examples :as examples]
    [com.repldriven.queenswood.party-api.links :as links]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the party schemas, keyed by the name each
  appears under in the document's `components/schemas`: `PartyType`,
  `PartyStatus`, `IdentifierType`, `Party`, `PartyDetail`,
  `PartyEmbedQuery`, `NationalIdentifier`, `Address`,
  `CreatePartyRequest`, `CreatePartyResponse`, `PartyList`,
  `MergePartyRequest`, `MergePartyResponse`, `SuspendPartyResponse`,
  `ResumePartyResponse`, `ClosePartyResponse`. Merged into the
  coercion registry in `api.clj`, so `[:ref \"X\"]` resolves them on
  any route."}
  registry
  components/registry)

(defn ->body
  "Project a stored party onto the keys `Party` declares, in the shape
  the list route returns. A record field the component does not
  declare — `:idempotency-key` — cannot reach a body through it.

  Args:
  - party: a party as the query brick hands it back."
  [party]
  (components/->body party))

(defn ->wire-body
  "Project a stored party and encode it as a read route would: enums as
  their wire strings, timestamps as ISO-8601. The bytes a webhook
  notification carrying the party renders, so a `Party` reaches a
  client in one spelling however it arrives.

  Args:
  - party: a party as the query brick hands it back."
  [party]
  (components/->wire-body party))

;; ---
;; coercion
;; ---

(defn party-status-enum-schema
  "The `PartyStatus` `:enum` schema, coercing between the wire strings
  (`pending`, `active`, `suspended`, `closed`, `rejected`, `merged`)
  and the record's prefixed keywords. A party whose stored status reads
  back unset encodes as `unknown`.

  Args:
  - extra-props (optional): map of schema properties merged over the
    enum's own, for a `:json-schema/example` and the like."
  ([] (coercion/party-status-enum-schema))
  ([extra-props] (coercion/party-status-enum-schema extra-props)))

;; ---
;; links
;; ---

(def
  ^{:doc
    "OpenAPI 3 `links` for a merge response: the merged-away party, and
  the survivor its `merged-into-party-id` names."}
  from-merged-party
  links/from-merged-party)

(def
  ^{:doc
    "OpenAPI 3 `links` for any response whose body is a `Party`: the
  party itself, keyed by operation id and parameterised from the
  response body."}
  from-party
  links/from-party)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the party bodies, feeding
  the document's `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:party/identification-rejected`
  rejection: Identification rejected for this party."}
  IdentificationRejected
  examples/IdentificationRejected)

(def
  ^{:doc
    "An active person, as `Party`'s `json-schema/example` and as the
  party a bank example embeds."}
  Party
  examples/Party)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:party/invalid-status` rejection: Party
  is not in a valid state for this action."}
  PartyInvalidStatus
  examples/PartyInvalidStatus)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:party/merge-into-self` rejection: Cannot
  merge a party into itself."}
  PartyMergeIntoSelf
  examples/PartyMergeIntoSelf)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:party/not-found` rejection: Party not
  found."}
  PartyNotFound
  examples/PartyNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:party/open-accounts` rejection: Party
  has open cash accounts."}
  PartyOpenAccounts
  examples/PartyOpenAccounts)
