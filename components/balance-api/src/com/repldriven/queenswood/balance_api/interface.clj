(ns com.repldriven.queenswood.balance-api.interface
  "A cash account's balances as the banking API publishes them: the
  malli components, the examples, and the balance a bank example
  embeds."
  (:require
    [com.repldriven.queenswood.balance-api.components :as components]
    [com.repldriven.queenswood.balance-api.examples :as examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the balance schemas, keyed by the name each
  appears under in the document's `components/schemas`:
  `BalanceType`, `BalanceStatus`, `Balance`, `BalanceList`,
  `BalanceProduct`. Merged into the coercion registry in `api.clj`,
  so `[:ref \"X\"]` resolves them on any route."}
  registry
  components/registry)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the balance bodies,
  feeding the document's `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "One balance of a current account, as the balance routes return it
  and as a bank example embeds it."}
  Balance
  examples/Balance)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:balance/not-found` rejection: Balance
  not found."}
  BalanceNotFound
  examples/BalanceNotFound)
