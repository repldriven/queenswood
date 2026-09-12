(ns com.repldriven.queenswood.api.cash-account-migration.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private status-enum
  (coercion/enum-coercion {"draft" :cash-account-migration-status-draft
                           "approved" :cash-account-migration-status-approved
                           "completed" :cash-account-migration-status-completed
                           "cancelled" :cash-account-migration-status-cancelled}
                          :cash-account-migration-status-unknown))

(def ^:private run-status-enum
  (coercion/enum-coercion {"running" :cash-account-migration-run-status-running
                           "completed"
                           :cash-account-migration-run-status-completed
                           "failed" :cash-account-migration-run-status-failed}
                          :cash-account-migration-run-status-unknown))

(def ^:private outcome-enum
  (coercion/enum-coercion {"eligible" :cash-account-migration-outcome-eligible
                           "migrated" :cash-account-migration-outcome-migrated
                           "ineligible"
                           :cash-account-migration-outcome-ineligible
                           "failed" :cash-account-migration-outcome-failed}
                          :cash-account-migration-outcome-unknown))

;; Why an account was left behind. Surfaced as the wire vocabulary
;; rather than prose so a client can group a preview by reason, which is
;; the only way to read one at scale.
(def ^:private ineligibility-enum
  (coercion/enum-coercion
   {"currency-not-allowed"
    :cash-account-migration-ineligibility-currency-not-allowed
    "account-not-open" :cash-account-migration-ineligibility-account-not-open
    "already-on-target" :cash-account-migration-ineligibility-already-on-target
    "version-not-in-source"
    :cash-account-migration-ineligibility-version-not-in-source}
   :cash-account-migration-ineligibility-unknown))

(def status-enum-schema (:enum-schema status-enum))
(def run-status-enum-schema (:enum-schema run-status-enum))
(def outcome-enum-schema (:enum-schema outcome-enum))
(def ineligibility-enum-schema (:enum-schema ineligibility-enum))
