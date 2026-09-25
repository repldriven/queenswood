(ns com.repldriven.queenswood.simulate-api.interface
  "The simulated inbound transfer as the banking API publishes it:
  the malli components and the examples its body and the rejection
  bodies carry."
  (:require
    [com.repldriven.queenswood.simulate-api.components :as components]
    [com.repldriven.queenswood.simulate-api.examples :as examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the simulate schemas, keyed by the name each
  appears under in the document's `components/schemas`:
  `SimulateInboundTransferRequest`, `TransactionLeg`,
  `SimulateInboundTransferResponse`. Merged into the coercion
  registry in `api.clj`, so `[:ref \"X\"]` resolves them on any route."}
  registry
  components/registry)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the simulate bodies,
  feeding the document's `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:balance/not-found` rejection: Balance
  not found."}
  BalanceNotFound
  examples/BalanceNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:transaction/invalid-amount` rejection:
  Transaction amount must be positive."}
  InvalidAmount
  examples/InvalidAmount)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:ledger-account/closed` rejection: Ledger
  account is closed."}
  LedgerAccountClosed
  examples/LedgerAccountClosed)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:gl/missing-currency-account` rejection:
  Bank has no gl-account-code-cash-at-correspondent ledger account
  in USD."}
  MissingCurrencyAccount
  examples/MissingCurrencyAccount)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:bank/invalid-status` rejection: Only a
  test bank can simulate."}
  SimulateLiveBank
  examples/SimulateLiveBank)
