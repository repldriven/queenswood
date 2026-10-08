(ns com.repldriven.queenswood.interest.core
  (:require
    [com.repldriven.queenswood.interest.accrue :as accrue]
    [com.repldriven.queenswood.interest.capitalize :as capitalize]
    [com.repldriven.queenswood.interest.domain.chart :as chart]
    [com.repldriven.queenswood.interest.domain.run :as run]
    [com.repldriven.queenswood.interest.scan :as scan]
    [com.repldriven.queenswood.interest.store :as store]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- run-interest
  "Run an interest pass under the platform daily-count limit for
  `policy-kind`. Counts prior runs of this kind for the org on
  `as-of-date` and rejects if the limit is reached, then streams the
  bank's accounts and posts them a chunk at a time.

  The InterestRun record is written only once the pass has finished, so
  a crash part-way leaves no run record and the daily limit does not
  block the retry. What makes that retry safe is the DONE rows the
  chunks committed: a re-run streams every account again and skips the
  ones already posted, and posts the ones a failed chunk left FAILED. A
  pass with any account failed returns `:interest/run-incomplete` and
  writes no run record, so the run closes only once every account is
  posted.

  A chunk is a short FDB transaction — no long transaction is held
  across the run — and posts both sides of the books itself: accrual
  debits interest expense, and capitalisation moves each account's
  interest from 2400 to its deposit control, so nothing is posted at
  close.

  The chart of accounts is resolved before any account is touched, once
  per currency the chart carries, so a bank that cannot take the ledger
  side fails before the books go out rather than after. The one failure
  that cannot be caught up front is an account in a currency the chart
  carries no row of at all: the resolution reads the chart, which says
  nothing about it, so it surfaces when that account is posted."
  [config data spec]
  (let [{:keys [policy-kind run-kind gl-fn]} spec
        {:keys [bank-id as-of-date]} data
        ctx (assoc spec
                   :bank-id bank-id
                   :business-day as-of-date
                   ;; Run-scoped, so every account in the pass is
                   ;; accrued against the same view of the rates.
                   :versions (atom {})
                   :chunks-in-flight (:interest-chunks-in-flight config 1))]
    (let-nom>
      [chart-of-accounts (ledger-accounts/list-accounts config bank-id)
       gl (chart/by-currency chart-of-accounts bank-id gl-fn)
       policies (policy/get-effective-policies config {:bank-id bank-id})
       today-count (store/count-by-org-business-day-per-kind config
                                                             bank-id
                                                             run-kind
                                                             as-of-date)
       aggregates {policy-kind {#{:bank-id :business-day} today-count}}
       _ (run/check-daily-count policies policy-kind aggregates)
       tally (scan/post-accounts config (assoc ctx :gl gl :policies policies))
       _ (run/check-complete bank-id as-of-date tally)
       record (run/closed bank-id as-of-date run-kind)
       _ (store/save-run config record)]
      {:bank-id bank-id
       :as-of-date as-of-date
       :accounts-processed (+ (:done tally) (:skipped tally))
       :accounts-failed (:failed tally)
       :run-status (:status record)})))

(defn accrue-day [config data] (run-interest config data accrue/pass))

(defn capitalize-accrued
  [config data]
  (run-interest config data capitalize/pass))

(def ^:private kind->run {:accrue accrue/pass :capitalize capitalize/pass})

(defn run-progress
  [config bank-id as-of-date kind]
  (if-let [{:keys [run-kind account-kind]} (kind->run kind)]
    (let-nom>
      [scope (store/count-account-runs config bank-id as-of-date account-kind)
       done (store/count-account-runs-by-status
             config
             bank-id
             as-of-date
             account-kind
             :interest-account-run-status-done)
       failed (store/count-account-runs-by-status
               config
               bank-id
               as-of-date
               account-kind
               :interest-account-run-status-failed)
       run (store/load-run config bank-id as-of-date run-kind)]
      {:scope scope
       :done done
       :failed failed
       :pending (- scope done failed)
       :run-status (:status run)})
    (error/reject :interest/unknown-run-kind
                  {:message "Run kind must be :accrue or :capitalize"
                   :kind kind})))

(defn accrual-carry
  [config bank-id account-id]
  (store/transact config
                  (fn [txn]
                    (get (store/load-carries txn bank-id [account-id])
                         account-id))
                  :interest/accrual-carry
                  "Failed to read an account's accrual carry"))
