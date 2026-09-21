(ns com.repldriven.queenswood.reward-api.interface
  "The reward resource as the banking API publishes it: the malli
  components the document's `Reward` and its id, kind and status are
  built from, the example they carry, the enum schemas that coerce a
  status and a kind between wire strings and internal keywords, and
  the projections that turn a stored row into a response body — one
  in the record's own spelling, one in the wire's.

  Anything published about a reward is projected here, so a webhook
  notification carrying the resource emits the same shape a read route
  returns rather than a copy of it."
  (:require
    [com.repldriven.queenswood.reward-api.coercion :as coercion]
    [com.repldriven.queenswood.reward-api.components :as components]
    [com.repldriven.queenswood.reward-api.examples :as examples]))

(def
  ^{:doc
    "Malli registry of the reward schemas, keyed by the name each
  appears under in the document's `components/schemas`: `Reward`,
  `RewardList`, `RewardId`, `RewardKind` and `RewardStatus`."}
  registry
  components/registry)

(def
  ^{:doc
    "The 404 body a reward read answers for an id the bank does
  not hold, as the document's example."}
  RewardNotFound
  examples/RewardNotFound)

(def
  ^{:doc
    "Registry of the examples the reward routes' responses name, keyed
  by the name each appears under in the document's
  `components/examples`, merged into the document beside every other
  domain's."}
  examples
  examples/registry)

(def
  ^{:doc
    "The `RewardStatus` `:enum` schema, coercing between the wire
  strings (`due`, `paid`) and the internal keywords. Called with the
  schema's properties, or with none."}
  reward-status-enum-schema
  coercion/reward-status-enum-schema)

(def
  ^{:doc
    "The `RewardKind` `:enum` schema, coercing between the wire string
  `opening` and the internal keyword. Called with the schema's
  properties, or with none."}
  reward-kind-enum-schema
  coercion/reward-kind-enum-schema)

(defn ->body
  "The response body for a stored reward, in the record's own
  spelling: every key `Reward` declares and nothing the row also holds.

  Args:
  - reward: a reward as the query brick hands it back."
  [reward]
  (components/->body reward))

(defn ->wire-body
  "As `->body`, encoded as a route sends it: statuses and kinds in
  their wire spelling, timestamps as ISO 8601.

  Args:
  - reward: a reward as the query brick hands it back."
  [reward]
  (components/->wire-body reward))
