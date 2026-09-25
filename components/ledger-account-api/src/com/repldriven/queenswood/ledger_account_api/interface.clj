(ns com.repldriven.queenswood.ledger-account-api.interface
  "Ledger accounts and the trial balance as the banking API publishes
  them: the malli components and the examples their bodies and the
  rejection bodies carry."
  (:require
    [com.repldriven.queenswood.ledger-account-api.components :as components]
    [com.repldriven.queenswood.ledger-account-api.examples :as examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the ledger-account schemas, keyed by the name
  each appears under in the document's `components/schemas`:
  `LedgerAccountId`, `GlAccountType`, `GlAccountClass`, `Required`,
  `SubLedgerKind`, `LedgerAccountStatus`, `LedgerAccount`,
  `TrialBalanceEntry`, `LedgerAccountList`, `LedgerBalance`,
  `LedgerBalanceList`. Merged into the coercion registry in
  `api.clj`, so `[:ref \"X\"]` resolves them on any route."}
  registry
  components/registry)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the ledger-account
  bodies, feeding the document's `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:ledger-account/not-found` rejection:
  Ledger account not found."}
  LedgerAccountNotFound
  examples/LedgerAccountNotFound)
