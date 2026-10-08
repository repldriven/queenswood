(ns com.repldriven.queenswood.interest.domain.run
  (:require
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private kind->action
  {:accrual :interest-action-accrue :capitalize :interest-action-capitalize})

(defn check-daily-count
  [policies kind aggregates]
  (policy/check-limit
   policies
   :interest
   {:action (kind->action kind)
    :aggregate :count
    :window :time-window-daily
    :value (inc (get-in aggregates [kind #{:bank-id :business-day}]))}))

(def ^:private eligible-cash-account-statuses
  #{:cash-account-status-opened :cash-account-status-suspended})

(defn eligible-cash-account?
  [account]
  (contains? eligible-cash-account-statuses (:status account)))

(defn check-complete
  "The tally, or `:interest/run-incomplete` while any account in scope
  failed, so the run closes only once every account is done."
  [bank-id business-day tally]
  (let [{:keys [done skipped failed]} tally]
    (if (pos? failed)
      (error/fail :interest/run-incomplete
                  {:message "Interest run has accounts that failed to post"
                   :bank-id bank-id
                   :business-day business-day
                   :accounts-processed (+ done skipped)
                   :accounts-failed failed})
      tally)))

(defn- new-run
  [bank-id business-day kind]
  {:bank-id bank-id
   :business-day business-day
   :kind kind
   :state :interest-run-state-running
   :created-at (utility/now)})

(defn- close
  [run]
  (assoc run
         :state :interest-run-state-closed
         :closed-at (utility/now)))

(defn closed
  [bank-id business-day kind]
  (close (new-run bank-id business-day kind)))
