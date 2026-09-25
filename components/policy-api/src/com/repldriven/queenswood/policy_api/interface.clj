(ns com.repldriven.queenswood.policy-api.interface
  "Policies as the banking API publishes them: the malli components
  and the examples their bodies and the rejection bodies carry."
  (:require
    [com.repldriven.queenswood.policy-api.components :as components]
    [com.repldriven.queenswood.policy-api.examples :as examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the policy schemas, keyed by the name each
  appears under in the document's `components/schemas`: `PolicyId`,
  `PolicyCategory`, `Capability`, `Limit`, `Policy`, `PolicyList`,
  `Origin`, `EffectiveCapability`, `EffectiveLimit`,
  `EffectivePolicy`. Merged into the coercion registry in `api.clj`,
  so `[:ref \"X\"]` resolves them on any route."}
  registry
  components/registry)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the policy bodies,
  feeding the document's `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:policy/not-found` rejection: Policy not
  found."}
  PolicyNotFound
  examples/PolicyNotFound)
