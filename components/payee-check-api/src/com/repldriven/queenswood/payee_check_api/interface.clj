(ns com.repldriven.queenswood.payee-check-api.interface
  "Payee checks as the banking API publishes them: the malli
  components, the examples their bodies and the rejection bodies
  carry, and the OpenAPI `links` a check response advertises."
  (:require
    [com.repldriven.queenswood.payee-check-api.components :as components]
    [com.repldriven.queenswood.payee-check-api.examples :as examples]
    [com.repldriven.queenswood.payee-check-api.links :as links]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the payee-check schemas, keyed by the name each
  appears under in the document's `components/schemas`: `CheckId`,
  `PayeeCheckAccountType`, `MatchResult`, `PayeeCheckAccount`,
  `PayeeCheckRequest`, `PayeeCheckResult`, `PayeeCheck`,
  `PayeeCheckList`. Merged into the coercion registry in `api.clj`,
  so `[:ref \"X\"]` resolves them on any route."}
  registry
  components/registry)

;; ---
;; links
;; ---

(def ^{:doc "OpenAPI 3 `links` for any response whose body is a payee check."}
     from-check
  links/from-check)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the payee-check bodies,
  feeding the document's `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:payee-check/not-found` rejection: Payee
  check not found."}
  PayeeCheckNotFound
  examples/PayeeCheckNotFound)
