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

(def ^:private interest-banding-enum
  (coercion/enum-coercion {"marginal" :interest-banding-marginal
                           "whole-balance" :interest-banding-whole-balance}))

(def ^:private interest-period-unit-enum
  (coercion/enum-coercion {"day" :interest-period-unit-day
                           "week" :interest-period-unit-week
                           "month" :interest-period-unit-month}))

(def ^:private interest-day-count-enum
  (coercion/enum-coercion {"actual-365" :interest-day-count-actual-365
                           "actual-actual" :interest-day-count-actual-actual
                           "actual-360" :interest-day-count-actual-360}))

(def ^:private business-day-convention-enum
  (coercion/enum-coercion
   {"unadjusted" :business-day-convention-unadjusted
    "following" :business-day-convention-following
    "modified-following" :business-day-convention-modified-following
    "preceding" :business-day-convention-preceding
    "modified-preceding" :business-day-convention-modified-preceding}))

(def interest-banding-enum-schema (:enum-schema interest-banding-enum))
(def interest-period-unit-enum-schema (:enum-schema interest-period-unit-enum))
(def interest-day-count-enum-schema (:enum-schema interest-day-count-enum))
(def business-day-convention-enum-schema
  (:enum-schema business-day-convention-enum))
(def payment-direction-enum-schema (:enum-schema payment-direction-enum))
(def payment-measure-enum-schema (:enum-schema payment-measure-enum))
(def payment-period-enum-schema (:enum-schema payment-period-enum))
(def payment-period-anchor-enum-schema
  (:enum-schema payment-period-anchor-enum))
(def balance-sheet-side-enum-schema (:enum-schema balance-sheet-side-enum))
(def version-status-enum-schema (:enum-schema version-status-enum))
