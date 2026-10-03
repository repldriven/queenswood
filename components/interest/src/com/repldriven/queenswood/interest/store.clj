(ns com.repldriven.queenswood.interest.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def ^:private interest-runs-store-name "interest-runs")

(def ^:private interest-account-runs-store-name "interest-account-runs")

(def transact fdb/transact)

(defn save-run
  [txn run]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn interest-runs-store-name)
                      (schema/InterestRun->java run)))
   :interest/save-run
   "Failed to save interest run"))

(defn load-run
  [txn bank-id business-day kind]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn interest-runs-store-name)
                              bank-id
                              business-day
                              (schema/interest-run-kind->int kind))
             schema/pb->InterestRun))
   :interest/load-run
   {:message "Failed to load interest run"
    :bank-id bank-id
    :business-day business-day}))

(defn count-by-org-business-day-per-kind
  [txn bank-id kind business-day]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/count-records
      (fdb/open txn interest-runs-store-name)
      "InterestRun_count_by_bank_kind_business_day"
      [bank-id (schema/interest-run-kind->int kind) business-day]))
   :interest/count-by-org-business-day-per-kind
   {:message "Failed to count interest runs by org/day/kind"
    :bank-id bank-id
    :business-day business-day}))

(defn load-account-run
  "Reads one account's row for a run. Returns nil when absent, which is
  how enumeration tells a fresh account from one a prior attempt
  already finished."
  [txn bank-id business-day kind account-id]
  (some-> (fdb/load-record (fdb/open txn interest-account-runs-store-name)
                           bank-id
                           business-day
                           (schema/interest-account-run-kind->int kind)
                           account-id)
          schema/pb->InterestAccountRun))

(defn save-account-run
  "Writes one account's row. Joins the caller's transaction so the DONE
  flip commits with the posting it records."
  [txn account-run]
  (fdb/save-record (fdb/open txn interest-account-runs-store-name)
                   (schema/InterestAccountRun->java account-run)))

(defn count-account-runs
  "Scope size for a run — how many accounts were enumerated. Read at
  SNAPSHOT: the aggregate key must not join the read-conflict set, or
  polling progress serialises every concurrent posting in the bank."
  [txn bank-id business-day kind]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/count-records-snapshot
      (fdb/open txn interest-account-runs-store-name)
      "InterestAccountRun_count_by_bank_day_kind"
      [bank-id business-day (schema/interest-account-run-kind->int kind)]))
   :interest/count-account-runs
   {:message "Failed to count interest account runs"
    :bank-id bank-id
    :business-day business-day}))

(defn sum-account-run-amounts
  "What the run accrued across every account, for the single ledger
  entry posted at close. SNAPSHOT for the same reason as the counts —
  a SERIALIZABLE aggregate read joins the conflict set and would
  re-serialise the pass it is measuring."
  [txn bank-id business-day kind currency]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/sum-records
      (fdb/open txn interest-account-runs-store-name)
      "InterestAccountRun_sum_amount_by_bank_day_kind_currency"
      [bank-id
       business-day
       (schema/interest-account-run-kind->int kind)
       currency]))
   :interest/sum-account-run-amounts
   {:message "Failed to sum interest account run amounts"
    :bank-id bank-id
    :business-day business-day}))

(defn count-account-runs-by-state
  "Rows in one state for a run — progress when `state` is DONE, residue
  when FAILED, outstanding work when PENDING. SNAPSHOT for the same
  reason as `count-account-runs`."
  [txn bank-id business-day kind state]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/count-records-snapshot
      (fdb/open txn interest-account-runs-store-name)
      "InterestAccountRun_count_by_bank_day_kind_state"
      [bank-id
       business-day
       (schema/interest-account-run-kind->int kind)
       (schema/interest-account-run-state->int state)]))
   :interest/count-account-runs-by-state
   {:message "Failed to count interest account runs by state"
    :bank-id bank-id
    :business-day business-day}))
