(ns com.repldriven.queenswood.cash-account-product-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private balance-sheet-side-enum
  (coercion/enum-coercion {"asset" :balance-sheet-side-asset
                           "liability" :balance-sheet-side-liability}
                          :balance-sheet-side-unknown))

(def ^:private version-status-enum
  (coercion/enum-coercion {"draft" :version-status-draft
                           "published" :version-status-published
                           "discarded" :version-status-discarded}
                          :version-status-unknown))

(def ^:private payment-direction-enum
  (coercion/enum-coercion {"in" :payment-direction-in
                           "out" :payment-direction-out}))

(def ^:private payment-measure-enum
  (coercion/enum-coercion {"amount" :amount "count" :count}))

(def ^:private payment-period-enum
  (coercion/enum-coercion {"payment" :time-window-instant
                           "day" :time-window-daily
                           "week" :time-window-weekly
                           "month" :time-window-monthly
                           "quarter" :time-window-quarterly
                           "year" :time-window-annually}))

(def ^:private payment-period-anchor-enum
  (coercion/enum-coercion {"account" :window-anchor-account
                           "calendar" :window-anchor-calendar}))

(def payment-direction-enum-schema (:enum-schema payment-direction-enum))
(def payment-measure-enum-schema (:enum-schema payment-measure-enum))
(def payment-period-enum-schema (:enum-schema payment-period-enum))
(def payment-period-anchor-enum-schema
  (:enum-schema payment-period-anchor-enum))
(def balance-sheet-side-enum-schema (:enum-schema balance-sheet-side-enum))
(def version-status-enum-schema (:enum-schema version-status-enum))
