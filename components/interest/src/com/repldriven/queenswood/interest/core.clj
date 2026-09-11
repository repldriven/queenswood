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

(defn- post-run-entries
  "Posts the bank's side once the accounts are done, each entry against
  the ledger accounts of its own currency. Short-circuits on the first
  anomaly — the remaining entries would post into the same broken
  chart."
  [spec config ctx gl entries]
  (let [{:keys [entry-fn entry-currency]} spec
        bank-id (:bank-id ctx)]
    (reduce (fn [_ entry]
              (let [result (let-nom>
                             [currency-gl (chart/accounts-for
                                           gl
                                           bank-id
                                           (entry-currency entry))]
                             (entry-fn config ctx currency-gl entry))]
                (if (error/anomaly? result) (reduced result) nil)))
            nil
            entries)))

(defn- run-interest
  "Run an interest pass under the platform daily-count limit for
  `policy-kind`. Counts prior runs of this kind for the org on
  `as-of-date` and rejects if the limit is reached, then streams the
  bank's accounts and posts them a chunk at a time.

  The InterestRun record is written only once the pass has finished, so
  a crash part-way leaves no run record and the daily limit does not
  block the retry. What makes that retry safe is the DONE rows the
  chunks committed: a re-run streams every account again and skips the
  ones already posted. A run that finished with failures still closes;
  its residue is the count of FAILED rows, which `run-progress`
  reports.

  A chunk is a short FDB transaction — no long transaction is held
  across the run. Both kinds then post the bank's side at close, which
  is the other half of a double entry the per-account postings
  deliberately leave open: accrual once per currency, capitalisation
  once per currency and product type.

  The chart of accounts is resolved before any account is touched, once
  per currency the chart carries, so a run posting in two currencies
  names two 5100s and two 2400s. Both kinds move customer money as they
  go, so a bank that cannot take the ledger side should fail before the
  books go out rather than after. The one failure that cannot be caught
  up front is an account in a currency the chart carries no row of at
  all: the resolution reads the chart, which says nothing about it, so
  it surfaces when that currency's entry is posted at close."
  [config data spec]
  (let [{:keys [policy-kind run-kind gl-fn entries-fn]} spec
        {:keys [bank-id as-of-date]} data
        ctx (assoc spec
                   :bank-id bank-id
                   :business-day as-of-date
                   ;; Run-scoped, so every account in the pass is
                   ;; accrued against the same view of the rates.
                   :versions (atom {}))]
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
       tally (scan/post-accounts config ctx)
       _ (post-run-entries spec config ctx gl (entries-fn (:seen tally)))
       record (run/closed bank-id as-of-date run-kind)
       _ (store/save-run config record)]
      {:bank-id bank-id
       :as-of-date as-of-date
       :accounts-processed (+ (:done tally) (:skipped tally))
       :accounts-failed (:failed tally)
       :run-state (:state record)})))

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
       done (store/count-account-runs-by-state
             config
             bank-id
             as-of-date
             account-kind
             :interest-account-run-state-done)
       failed (store/count-account-runs-by-state
               config
               bank-id
               as-of-date
               account-kind
               :interest-account-run-state-failed)
       run (store/load-run config bank-id as-of-date run-kind)]
      {:scope scope
       :done done
       :failed failed
       :pending (- scope done failed)
       :run-state (:state run)})
    (error/reject :interest/unknown-run-kind
                  {:message "Run kind must be :accrue or :capitalize"
                   :kind kind})))
