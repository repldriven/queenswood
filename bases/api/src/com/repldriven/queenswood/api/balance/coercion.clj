(ns com.repldriven.queenswood.api.balance.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private balance-type-enum
  (coercion/enum-coercion {"default" :balance-type-default
                           "interest-accrued" :balance-type-interest-accrued
                           "purchase" :balance-type-purchase
                           "cash" :balance-type-cash
                           "suspense" :balance-type-suspense
                           "interest-payable" :balance-type-interest-payable}
                          :balance-type-unknown))

(def ^:private balance-status-enum
  (coercion/enum-coercion {"posted" :balance-status-posted
                           "pending-incoming" :balance-status-pending-incoming
                           "pending-outgoing" :balance-status-pending-outgoing}
                          :balance-status-unknown))

(def balance-type-enum-schema (:enum-schema balance-type-enum))
(def balance-status-enum-schema (:enum-schema balance-status-enum))
