(ns com.repldriven.queenswood.cash-account-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private cash-account-status-enum
  (coercion/enum-coercion {"opening" :cash-account-status-opening
                           "opened" :cash-account-status-opened
                           "closing" :cash-account-status-closing
                           "closed" :cash-account-status-closed
                           "suspended" :cash-account-status-suspended
                           "refused" :cash-account-status-refused}))

(def cash-account-status-enum-schema (:enum-schema cash-account-status-enum))

(def ^:private address-rotation-status-enum
  (coercion/enum-coercion {"pending" :address-rotation-status-pending
                           "completed" :address-rotation-status-completed
                           "failed" :address-rotation-status-failed}
                          :address-rotation-status-unknown))

(def address-rotation-status-enum-schema
  (:enum-schema address-rotation-status-enum))

(def ^:private account-type-enum
  (coercion/enum-coercion {"personal" :account-type-personal
                           "business" :account-type-business}
                          :account-type-unknown))

(def account-type-enum-schema (:enum-schema account-type-enum))
