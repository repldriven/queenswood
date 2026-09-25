(ns com.repldriven.queenswood.cash-account-migration-api.interface
  "Cash-account migrations as the banking API publishes them: the
  malli components a migration, its runs and its preview are built
  from, and the examples those bodies and the rejection bodies
  carry."
  (:require
    [com.repldriven.queenswood.cash-account-migration-api.components :as
     components]
    [com.repldriven.queenswood.cash-account-migration-api.examples :as
     examples]))

;; ---
;; schemas
;; ---

(def
  ^{:doc
    "Malli registry of the cash-account migration schemas, keyed by the
  name each appears under in the document's `components/schemas`:
  `MigrationId`, `MigrationRunId`, `MigrationStatus`,
  `MigrationRunStatus`, `MigrationOutcome`,
  `MigrationIneligibility`, `Migration`, `MigrationList`,
  `MigrationCreate`, `MigrationRun`, `MigrationRunList`,
  `MigrationAccountRun`, `MigrationAccountRunList`. Merged into the
  coercion registry in `api.clj`, so `[:ref \"X\"]` resolves them on
  any route."}
  registry
  components/registry)

;; ---
;; examples
;; ---

(def
  ^{:doc
    "Map of example name to example value for the cash-account
  migration bodies, feeding the document's `components/examples`
  section."}
  examples
  examples/registry)

(def
  ^{:doc
    "RFC 9457 body for a 409 `:cash-account-migration/invalid-status`
  rejection: Migration cannot be approved."}
  InvalidStatus
  examples/InvalidStatus)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:cash-account-migration/not-found`
  rejection: Migration not found."}
  MigrationNotFound
  examples/MigrationNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:cash-account-migration/name-required`
  rejection: A migration needs a name."}
  NameRequired
  examples/NameRequired)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:cash-account-migration/notice-after-due`
  rejection: Customers must be notified before accounts move."}
  NoticeAfterDue
  examples/NoticeAfterDue)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:cash-account-migration/notice-required`
  rejection: A migration needs a notice date and a due date before
  approval."}
  NoticeRequired
  examples/NoticeRequired)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:cash-account-migration/product-type-
  mismatch` rejection: Source and target must be the same product
  type."}
  ProductTypeMismatch
  examples/ProductTypeMismatch)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:cash-account-migration/run-not-found`
  rejection: Migration run not found."}
  RunNotFound
  examples/RunNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 404 `:cash-account-migration/source-product-
  not-found` rejection: Source product has no versions."}
  SourceProductNotFound
  examples/SourceProductNotFound)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:cash-account-migration/target-is-source`
  rejection: A migration's target must differ from its source."}
  TargetIsSource
  examples/TargetIsSource)

(def
  ^{:doc
    "RFC 9457 body for a 422 `:cash-account-migration/target-not-
  published` rejection: A migration's target version must be
  published."}
  TargetNotPublished
  examples/TargetNotPublished)
