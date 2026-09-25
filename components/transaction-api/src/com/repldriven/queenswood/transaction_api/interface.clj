(ns com.repldriven.queenswood.transaction-api.interface
  "Transactions as the banking API publishes them, embedded under a
  cash account: the malli components their bodies are built from."
  (:require
    [com.repldriven.queenswood.transaction-api.components :as components]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the transaction schemas, keyed by the name each
  appears under in the document's `components/schemas`:
  `TransactionId`, `LegId`, `TransactionStatus`, `TransactionType`,
  `LegSide`, `Transaction`, `TransactionList`. Merged into the
  coercion registry in `api.clj`, so `[:ref \"X\"]` resolves them on
  any route."}
  registry
  components/registry)
