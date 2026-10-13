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

(def ^:private interest-schedule-basis-enum
  (coercion/enum-coercion {"fixed" :interest-schedule-basis-fixed
                           "relative" :interest-schedule-basis-relative}))

(def ^:private interest-banding-enum
  (coercion/enum-coercion {"marginal" :interest-banding-marginal
                           "whole-balance" :interest-banding-whole-balance}))

(def ^:private interest-day-count-enum
  (coercion/enum-coercion {"actual-365" :interest-day-count-actual-365
                           "actual-actual" :interest-day-count-actual-actual}))

(def ^:private interest-payment-frequency-enum
  (coercion/enum-coercion {"daily" :interest-payment-frequency-daily
                           "monthly" :interest-payment-frequency-monthly
                           "quarterly" :interest-payment-frequency-quarterly
                           "annually" :interest-payment-frequency-annually
                           "at-close" :interest-payment-frequency-at-close}))

(def ^:private interest-payment-day-enum
  (coercion/enum-coercion {"account" :interest-payment-day-account
                           "day-of-month" :interest-payment-day-day-of-month
                           "last-of-month"
                           :interest-payment-day-last-of-month}))

(def interest-schedule-basis-enum-schema
  (:enum-schema interest-schedule-basis-enum))
(def interest-banding-enum-schema (:enum-schema interest-banding-enum))
(def interest-day-count-enum-schema (:enum-schema interest-day-count-enum))
(def interest-payment-frequency-enum-schema
  (:enum-schema interest-payment-frequency-enum))
(def interest-payment-day-enum-schema (:enum-schema interest-payment-day-enum))
(def payment-direction-enum-schema (:enum-schema payment-direction-enum))
(def payment-measure-enum-schema (:enum-schema payment-measure-enum))
(def payment-period-enum-schema (:enum-schema payment-period-enum))
(def payment-period-anchor-enum-schema
  (:enum-schema payment-period-anchor-enum))
(def balance-sheet-side-enum-schema (:enum-schema balance-sheet-side-enum))
(def version-status-enum-schema (:enum-schema version-status-enum))
