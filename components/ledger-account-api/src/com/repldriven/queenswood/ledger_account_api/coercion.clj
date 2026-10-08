(ns com.repldriven.queenswood.ledger-account-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private gl-account-type-enum
  (coercion/enum-coercion {"asset" :ledger-account-class-asset
                           "liability" :ledger-account-class-liability
                           "equity" :ledger-account-class-equity
                           "income" :ledger-account-class-income
                           "expense" :ledger-account-class-expense}
                          :ledger-account-class-unknown))

(def ^:private gl-account-class-enum
  (coercion/enum-coercion {"detail" :ledger-account-type-detail
                           "control" :ledger-account-type-control
                           "summary" :ledger-account-type-summary}
                          :ledger-account-type-unknown))

(def ^:private ledger-account-status-enum
  (coercion/enum-coercion {"open" :ledger-account-status-open
                           "closed" :ledger-account-status-closed}
                          :ledger-account-status-unknown))

(def gl-account-type-enum-schema (:enum-schema gl-account-type-enum))
(def gl-account-class-enum-schema (:enum-schema gl-account-class-enum))
(def ledger-account-status-enum-schema
  (:enum-schema ledger-account-status-enum))