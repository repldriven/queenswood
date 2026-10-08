(ns com.repldriven.queenswood.ledger-account-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private gl-account-type-enum
  (coercion/enum-coercion {"asset" :gl-account-class-asset
                           "liability" :gl-account-class-liability
                           "equity" :gl-account-class-equity
                           "income" :gl-account-class-income
                           "expense" :gl-account-class-expense}
                          :gl-account-class-unknown))

(def ^:private gl-account-class-enum
  (coercion/enum-coercion {"detail" :gl-account-type-detail
                           "control" :gl-account-type-control
                           "summary" :gl-account-type-summary}
                          :gl-account-type-unknown))

(def ^:private ledger-account-status-enum
  (coercion/enum-coercion {"open" :ledger-account-status-open
                           "closed" :ledger-account-status-closed}
                          :ledger-account-status-unknown))

(def gl-account-type-enum-schema (:enum-schema gl-account-type-enum))
(def gl-account-class-enum-schema (:enum-schema gl-account-class-enum))
(def ledger-account-status-enum-schema
  (:enum-schema ledger-account-status-enum))