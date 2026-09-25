(ns com.repldriven.queenswood.tier-api.interface
  "Tiers as the banking API publishes them: the malli components and
  the examples their bodies carry."
  (:require
    [com.repldriven.queenswood.tier-api.components :as components]
    [com.repldriven.queenswood.tier-api.examples :as examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the tier schemas, keyed by the name each appears
  under in the document's `components/schemas`: `Tier`, `TierList`.
  Merged into the coercion registry in `api.clj`, so `[:ref \"X\"]`
  resolves them on any route."}
  registry
  components/registry)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the tier bodies, feeding
  the document's `components/examples` section."}
  examples
  examples/registry)
