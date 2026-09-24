(ns com.repldriven.queenswood.cash-account-migration.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def ^:private store-name "cash-account-migrations")

(defn- drop-defaults
  "One record as it comes off the wire, as a plain map with the values
  proto2 hands back for fields that were never set removed — an empty
  string for an absent string, and the `-unknown` member for an absent
  enum.

  The map conversion is unconditional. `pb->` returns protojure records,
  which reitit cannot coerce, and relying on `dissoc` to convert one
  would leave a record behind whenever nothing happened to be dropped.

  `unknowns` maps a key to the enum member that means unset."
  [m string-keys unknowns]
  (as-> (into {} m) record
    (reduce (fn [acc k] (cond-> acc (= "" (get acc k)) (dissoc k)))
            record
            string-keys)
    (reduce (fn [acc [k unset]]
              (cond-> acc (= unset (get acc k)) (dissoc k)))
            record
            unknowns)))

(defn- clean-migration
  [migration]
  (drop-defaults migration [:idempotency-key] {}))

(defn- clean-run
  [run]
  (cond-> (drop-defaults run [:error] {})
          (zero? (:finished-at run 0))
          (dissoc :finished-at)))

(defn- clean-account-run
  "`to-version-id` is set only where an account moved, `failure-reason`
  only where one errored, and `ineligibility` only where one was left
  behind. All three come back as their proto2 defaults otherwise, and
  keeping them would say an eligible account had an unknown reason for
  not moving."
  [account-run]
  (drop-defaults account-run
                 [:to-version-id :failure-reason]
                 {:ineligibility
                  :cash-account-migration-ineligibility-unknown}))

(def transact fdb/transact)

(defn save-migration
  [txn migration]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn store-name)
                      (schema/CashAccountMigration->java migration)))
   :cash-account-migration/save
   "Failed to save cash-account migration"))

(defn load-migration
  "One migration by id. Nil when absent — the caller decides whether
  that is a rejection."
  [txn bank-id migration-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn store-name)
                              bank-id
                              migration-id)
             schema/pb->CashAccountMigration
             clean-migration))
   :cash-account-migration/load
   {:message "Failed to load cash-account migration"
    :bank-id bank-id
    :migration-id migration-id}))

(defn list-migrations
  "A bank's migrations, newest first — migration-id is a uuidv7, so
  descending primary-key order is reverse-chronological."
  [txn bank-id opts]
  (fdb/transact
   txn
   (fn [txn]
     (let [{:keys [after before limit order] :or {limit 1000 order :desc}}
           opts
           result (fdb/scan-records (fdb/open txn store-name)
                                    {:prefix [bank-id]
                                     :after after
                                     :before before
                                     :limit limit
                                     :order order})]
       {:migrations (mapv (comp clean-migration schema/pb->CashAccountMigration)
                          (:records result))
        :before (:before result)
        :after (:after result)}))
   :cash-account-migration/list
   {:message "Failed to list cash-account migrations" :bank-id bank-id}))

(def ^:private runs-store-name "cash-account-migration-runs")

(def ^:private account-runs-store-name "cash-account-migration-account-runs")

(defn save-run
  [txn run]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn runs-store-name)
                      (schema/CashAccountMigrationRun->java run)))
   :cash-account-migration/save-run
   "Failed to save cash-account migration run"))

(defn load-run
  [txn bank-id run-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn runs-store-name) bank-id run-id)
             schema/pb->CashAccountMigrationRun
             clean-run))
   :cash-account-migration/load-run
   {:message "Failed to load cash-account migration run"
    :bank-id bank-id
    :run-id run-id}))

(defn list-runs-by-migration
  "Runs of one migration, newest first — run-id is a uuidv7, so
  descending primary-key order is reverse-chronological."
  [txn bank-id migration-id]
  (fdb/transact
   txn
   (fn [txn]
     (->> (fdb/scan-records (fdb/open txn runs-store-name)
                            {:prefix [bank-id] :limit 1000 :order :desc})
          :records
          (map (comp clean-run schema/pb->CashAccountMigrationRun))
          (filter (fn [r] (= migration-id (:migration-id r))))
          vec))
   :cash-account-migration/list-runs
   {:message "Failed to list cash-account migration runs"
    :bank-id bank-id
    :migration-id migration-id}))

(defn save-account-run
  "One account's verdict. Joins the caller's transaction so a chunk of
  verdicts commits together or not at all."
  [txn account-run]
  (fdb/save-record (fdb/open txn account-runs-store-name)
                   (schema/CashAccountMigrationAccountRun->java account-run)))

(defn list-account-runs
  "Every verdict a run recorded, in account order."
  [txn bank-id run-id opts]
  (fdb/transact
   txn
   (fn [txn]
     (let [{:keys [after before limit] :or {limit 1000}} opts
           result (fdb/scan-records (fdb/open txn account-runs-store-name)
                                    {:prefix [bank-id run-id]
                                     :after after
                                     :before before
                                     :limit limit
                                     :order :asc})]
       {:account-runs (mapv (comp clean-account-run
                                  schema/pb->CashAccountMigrationAccountRun)
                            (:records result))
        :before (:before result)
        :after (:after result)}))
   :cash-account-migration/list-account-runs
   {:message "Failed to list cash-account migration account runs"
    :bank-id bank-id
    :run-id run-id}))

(defn count-account-runs-by-outcome
  "How many accounts a run put in one outcome. Read at SNAPSHOT: an
  aggregate read at SERIALIZABLE would join the conflict set and
  re-serialise the pass it is measuring."
  [txn bank-id run-id outcome]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/count-records-snapshot
      (fdb/open txn account-runs-store-name)
      "CashAccountMigrationAccountRun_count_by_run_outcome"
      [bank-id run-id (schema/cash-account-migration-outcome->int outcome)]))
   :cash-account-migration/count-account-runs
   {:message "Failed to count cash-account migration account runs"
    :bank-id bank-id
    :run-id run-id}))

(defn count-previews-on
  "How many previews the bank has already run today — the figure a
  daily preview limit is measured against."
  [txn bank-id business-day]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/count-records-snapshot
      (fdb/open txn runs-store-name)
      "CashAccountMigrationRun_count_by_bank_day_dry_run"
      [bank-id business-day true]))
   :cash-account-migration/count-previews
   {:message "Failed to count cash-account migration previews"
    :bank-id bank-id
    :business-day business-day}))

(defn find-by-idempotency-key
  "The migration a previous create with this key authored, or nil. The
  index is unique per bank, so a retried create reads its own result
  back rather than authoring a second migration against the same
  accounts."
  [txn bank-id idempotency-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record-compound
              (fdb/open txn store-name)
              "CashAccountMigration"
              [["bank_id" bank-id] ["idempotency_key" idempotency-key]])
             schema/pb->CashAccountMigration
             clean-migration))
   :cash-account-migration/find-by-idempotency-key
   {:message "Failed to look up cash-account migration by idempotency key"
    :bank-id bank-id}))
