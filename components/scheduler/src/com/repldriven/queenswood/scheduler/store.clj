(ns com.repldriven.queenswood.scheduler.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def ^:private jobs-store "scheduler-jobs")
(def ^:private runs-store "scheduler-runs")

(def transact fdb/transact)

;; --- jobs -----------------------------------------------------------------

(defn save-job
  [txn job]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn jobs-store)
                      (schema/SchedulerJob->java job)))
   :scheduler/save-job
   "Failed to save scheduler job"))

(defn get-job
  [txn bank-id job-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn jobs-store) bank-id job-id)
             schema/pb->SchedulerJob))
   :scheduler/get-job
   {:message "Failed to load scheduler job"
    :bank-id bank-id
    :job-id job-id}))

(defn list-jobs
  [txn bank-id opts]
  (fdb/transact
   txn
   (fn [txn]
     (let [{:keys [after before limit] :or {limit 1000}} opts
           result (fdb/scan-records (fdb/open txn jobs-store)
                                    {:prefix [bank-id]
                                     :after after
                                     :before before
                                     :limit limit
                                     :order :asc})]
       {:jobs (mapv schema/pb->SchedulerJob (:records result))
        :before (:before result)
        :after (:after result)}))
   :scheduler/list-jobs
   {:message "Failed to list scheduler jobs" :bank-id bank-id}))

(defn list-all-jobs
  "Every job across all banks — used by the runner at start to register
  triggers. Small fixed set per bank, so a full scan is fine."
  [txn]
  (fdb/transact
   txn
   (fn [txn]
     (mapv schema/pb->SchedulerJob
           (:records (fdb/scan-records (fdb/open txn jobs-store)
                                       {:limit 10000 :order :asc}))))
   :scheduler/list-all-jobs
   "Failed to list all scheduler jobs"))

;; --- runs -----------------------------------------------------------------

(defn save-run
  [txn run]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn runs-store)
                      (schema/SchedulerRun->java run)))
   :scheduler/save-run
   "Failed to save scheduler run"))

(defn get-run
  [txn bank-id run-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn runs-store) bank-id run-id)
             schema/pb->SchedulerRun))
   :scheduler/get-run
   {:message "Failed to load scheduler run"
    :bank-id bank-id
    :run-id run-id}))

(defn list-runs-by-job
  "Runs of one job, newest first (run-id is a uuidv7, so descending PK
  order is reverse-chronological). Scans the bank's runs and filters by
  job — fine while run cardinality per bank is low."
  [txn bank-id job-id]
  (fdb/transact
   txn
   (fn [txn]
     (->> (fdb/scan-records (fdb/open txn runs-store)
                            {:prefix [bank-id] :limit 10000 :order :desc})
          :records
          (map schema/pb->SchedulerRun)
          (filter #(= job-id (:job-id %)))
          vec))
   :scheduler/list-runs-by-job
   {:message "Failed to list scheduler runs" :bank-id bank-id :job-id job-id}))
