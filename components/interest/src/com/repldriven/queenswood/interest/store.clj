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

(def ^:private carry-index
  "InterestAccountRun_sum_carry_delta_by_bank_kind_account")

(defn load-account-runs
  "Reads the rows of `account-ids` for a run in one round trip, as a map
  of account id to row, an account with none absent."
  [txn bank-id business-day kind account-ids]
  (let [kind (schema/interest-run-kind->int kind)
        records (fdb/load-records
                 (fdb/open txn interest-account-runs-store-name)
                 (mapv (fn [id] [bank-id business-day kind id]) account-ids))]
    (into {}
          (keep (fn [[id record]]
                  (when record [id (schema/pb->InterestAccountRun record)])))
          (map vector account-ids records))))

(defn save-account-runs
  "Writes several accounts' rows, their reads issued together. Joins
  the caller's transaction so the DONE flips commit with the postings
  they record."
  [txn account-runs]
  (let [store (fdb/open txn interest-account-runs-store-name)]
    (fdb/save-records (mapv (fn [row] [store
                                       (schema/InterestAccountRun->java row)])
                            account-runs))))

(defn load-carries
  "The sub-unit carry each of `account-ids` opens its next accrual with,
  as a map of account id to carry: the sum of its accrual rows' carry
  changes, zero for an account none has. One scan per account, issued
  together and read at SNAPSHOT, since only the account's own accrual
  writes them."
  [txn bank-id account-ids]
  (let [store (fdb/open txn interest-account-runs-store-name)
        kind (schema/interest-run-kind->int
              :interest-run-kind-accrue)
        scans (mapv (fn [id]
                      (fdb/sum-groups-later store
                                            carry-index
                                            [bank-id kind id]
                                            {:isolation :snapshot}))
                    account-ids)]
    (zipmap account-ids
            (map (fn [scan] (reduce + 0 (vals (scan)))) scans))))

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
      [bank-id business-day (schema/interest-run-kind->int kind)]))
   :interest/count-account-runs
   {:message "Failed to count interest account runs"
    :bank-id bank-id
    :business-day business-day}))

(defn count-account-runs-by-status
  "Rows at one status for a run — progress when `status` is DONE,
  residue when FAILED, outstanding work when PENDING. SNAPSHOT for the
  same reason as `count-account-runs`."
  [txn bank-id business-day kind status]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/count-records-snapshot
      (fdb/open txn interest-account-runs-store-name)
      "InterestAccountRun_count_by_bank_day_kind_status"
      [bank-id
       business-day
       (schema/interest-run-kind->int kind)
       (schema/interest-account-run-status->int status)]))
   :interest/count-account-runs-by-status
   {:message "Failed to count interest account runs by status"
    :bank-id bank-id
    :business-day business-day}))
