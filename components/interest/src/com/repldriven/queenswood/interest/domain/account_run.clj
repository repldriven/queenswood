(ns com.repldriven.queenswood.interest.domain.account-run
  (:require
    [com.repldriven.mono.utility.interface :as utility]))

(defn- new-account-run
  [bank-id business-day kind account-id]
  (let [now (utility/now)]
    {:bank-id bank-id
     :business-day business-day
     :kind kind
     :account-id account-id
     :status :interest-account-run-status-pending
     :created-at now
     :updated-at now}))

(defn new
  "The run to record this account's outcome on: the one an earlier
  attempt left behind, or a fresh pending one."
  [bank-id business-day kind account existing]
  (or existing
      (new-account-run bank-id business-day kind (:account-id account))))

(defn done
  "Marks the run done and records what the account earned and what it
  was computed from — the `:amount`, the `:principal` and
  `:opening-carry` behind it, and the `:carry-delta` an accrual left.
  Each is omitted when absent rather than recorded as nil, and an
  account with nothing to do carries none of them."
  [account-run outcome]
  (utility/assoc-some (assoc account-run
                             :status :interest-account-run-status-done
                             :updated-at (utility/now))
                      :amount (:amount outcome)
                      :principal (:principal outcome)
                      :opening-carry (:opening-carry outcome)
                      :carry-delta (:carry-delta outcome)))

(defn failed
  "Marks the run failed so a pass can move past it. `reason` is the
  anomaly's category, kept short — the anomaly itself is logged."
  [account-run reason]
  (assoc account-run
         :status :interest-account-run-status-failed
         :failure-reason (str reason)
         :updated-at (utility/now)))

(defn done?
  [account-run]
  (= :interest-account-run-status-done (:status account-run)))
