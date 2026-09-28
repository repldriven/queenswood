(ns com.repldriven.queenswood.payment.domain.checks
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error])
  (:import
    (java.time Instant ZoneId)))

(defn current-business-day
  "Returns the epoch-day (long) of the business day that contains
  `now-ms` under `cutoff`. A timestamp that falls in a
  zone-local time-of-day before `:hour-of-day` counts as the
  *previous* day's bucket. A missing `cutoff` (nil or empty map)
  defaults to UTC midnight — i.e. plain calendar day in UTC."
  [^long now-ms cutoff]
  (let [{:keys [zone hour-of-day]
         :or {zone "UTC" hour-of-day 0}}
        cutoff]
    (-> (Instant/ofEpochMilli now-ms)
        (.atZone (ZoneId/of zone))
        (.minusHours (long hour-of-day))
        .toLocalDate
        .toEpochDay)))

(def ^:private operable-statuses #{:cash-account-status-opened})

(def ^:private role->not-operable
  {:debtor :payment/debtor-account-not-operable
   :creditor :payment/creditor-account-not-operable})

(defn operable?
  "True when money may move on `account` — it is opened. The status
  vocabulary the payment brick reads lives here alone."
  [account]
  (contains? operable-statuses (:account-status account)))

(defn ensure-account-operable
  "Rejects when `account` is not operable. `role` is `:debtor` or
  `:creditor` and selects the rejection kind."
  [account role]
  (when-not (operable? account)
    (error/reject (role->not-operable role)
                  {:message (str "The "
                                 (name role)
                                 " account is not open for payments")
                   :account-id (:account-id account)
                   :status (:account-status account)
                   :allowed operable-statuses})))

(defn ensure-currency-matches
  [payment-currency account]
  (when (not= payment-currency (:currency account))
    (error/reject :payment/currency-mismatch
                  {:message
                   "Payment currency must match account currency"
                   :payment-currency payment-currency
                   :account-id (:account-id account)
                   :account-currency (:currency account)})))

(defn check-capability
  [policies kind action]
  (policy/check-capability policies kind {:action action}))

(defn check-daily-count
  [policies kind aggregates]
  (policy/check-limit
   policies
   kind
   {:aggregate :count
    :window :time-window-daily
    :value (inc (get-in aggregates
                        [kind #{:bank-id :business-day}]))}))

(defn refused?
  [result]
  (or (error/rejection? result) (error/unauthorized? result)))
