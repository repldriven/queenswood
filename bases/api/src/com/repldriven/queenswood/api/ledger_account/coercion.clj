(ns com.repldriven.queenswood.api.ledger-account.coercion
  (:require
    [com.repldriven.queenswood.api.coercion :as coercion]))

(def ^:private gl-account-type-enum
  (coercion/enum-coercion {"asset" :gl-account-type-asset
                           "liability" :gl-account-type-liability
                           "equity" :gl-account-type-equity
                           "income" :gl-account-type-income
                           "expense" :gl-account-type-expense}
                          :gl-account-type-unknown))

(def ^:private gl-account-class-enum
  (coercion/enum-coercion {"detail" :gl-account-class-detail
                           "control" :gl-account-class-control
                           "summary" :gl-account-class-summary}
                          :gl-account-class-unknown))

(def ^:private required-enum
  (coercion/enum-coercion {"mandatory" :required-mandatory
                           "optional" :required-optional}
                          :required-unknown))

(def ^:private sub-ledger-kind-enum
  (coercion/enum-coercion
   {"cash-account-current" :sub-ledger-kind-cash-account-current
    "cash-account-savings" :sub-ledger-kind-cash-account-savings
    "cash-account-term-deposit" :sub-ledger-kind-cash-account-term-deposit}
   :sub-ledger-kind-unknown))

(def ^:private ledger-account-status-enum
  (coercion/enum-coercion {"open" :ledger-account-status-open
                           "closed" :ledger-account-status-closed}
                          :ledger-account-status-unknown))

(def gl-account-type-enum-schema (:enum-schema gl-account-type-enum))
(def gl-account-class-enum-schema (:enum-schema gl-account-class-enum))
(def required-enum-schema (:enum-schema required-enum))
(def sub-ledger-kind-enum-schema (:enum-schema sub-ledger-kind-enum))
(def ledger-account-status-enum-schema
  (:enum-schema ledger-account-status-enum))