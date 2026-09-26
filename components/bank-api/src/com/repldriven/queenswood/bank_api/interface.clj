(ns com.repldriven.queenswood.bank-api.interface
  "The bank resource as the banking API publishes it: the malli
  components a bank, its request bodies and its list are built from,
  and the examples those bodies and the rejection bodies carry."
  (:require
    [com.repldriven.queenswood.bank-api.components :as components]
    [com.repldriven.queenswood.bank-api.examples :as examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the bank schemas, keyed by the name each appears
  under in the document's `components/schemas`: `BankStatus`,
  `CreateBankRequest`, `Owner`, `Bank`, `BankList`,
  `CompanyBinding`, `CreateBankResponse`, `ChangeBankTierRequest`,
  `ChangeBankTierResponse`, `ChangeBankStatusRequest`,
  `ChangeBankStatusResponse`. Merged into the coercion registry in
  `api.clj`, so `[:ref \"X\"]` resolves them on any route."}
  registry
  components/registry)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the bank bodies, feeding
  the document's `components/examples` section."}
  examples
  examples/registry)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:bank/invalid-status` rejection: Bank is
  not in a tier-changeable state."}
  BankInvalidStatus
  examples/BankInvalidStatus)

(def ^{:doc
       "RFC 9457 body for a 404 `:bank/not-found` rejection: Bank not
  found."}
     BankNotFound
  examples/BankNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:bank/unknown-tier` rejection: No
  policies found for tier."}
  BankUnknownTier
  examples/BankUnknownTier)

(def
  ^{:doc
    "RFC 9457 body for a 403 `auth/forbidden` rejection: Name the bank
  in the Bank-Id header."}
  BankUnnamed
  examples/BankUnnamed)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:bank/company-not-active` rejection: Only
  an active company can be bound to a bank."}
  CompanyNotActive
  examples/CompanyNotActive)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:bank/company-required` rejection: Name
  the company the bank is created for."}
  CompanyRequired
  examples/CompanyRequired)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:idv/unsupported-criteria` rejection: the
  tier's policies require a verification or screening the identity
  provider does not establish."}
  IdvUnsupportedCriteria
  examples/IdvUnsupportedCriteria)

(def
  ^{:doc
    "RFC 9457 body for a 403 `auth/forbidden` rejection: Only an
  operator chooses a bank's status, tier, currencies or owner."}
  OperatorFieldRefused
  examples/OperatorFieldRefused)
