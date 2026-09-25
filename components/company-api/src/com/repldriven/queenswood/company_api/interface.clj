(ns com.repldriven.queenswood.company-api.interface
  "A company-registry lookup as the banking API publishes it: the
  malli components and the examples its body and rejection bodies
  carry."
  (:require
    [com.repldriven.queenswood.company-api.components :as components]
    [com.repldriven.queenswood.company-api.examples :as examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the company schemas, keyed by the name each
  appears under in the document's `components/schemas`:
  `RegisteredOfficeAddress`, `Company`. Merged into the coercion
  registry in `api.clj`, so `[:ref \"X\"]` resolves them on any route."}
  registry
  components/registry)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the company bodies,
  feeding the document's `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:company/not-found` rejection: No active
  company found for that number."}
  CompanyNotFound
  examples/CompanyNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 503 `:company/unavailable` rejection:
  Companies House unavailable."}
  CompanyRegistryUnavailable
  examples/CompanyRegistryUnavailable)
