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

;; ---
;; projections
;; ---

(defn ->body
  "Project a leg, as the transaction brick pages it, onto the keys
  `Transaction` declares: its `status` is posted where the leg moved a
  posted balance, and pending otherwise.

  Args:
  - leg: a leg map, enriched with its transaction's type, reference and
    creation time."
  [leg]
  (components/->body leg))
