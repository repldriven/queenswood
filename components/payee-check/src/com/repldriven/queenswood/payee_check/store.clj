(ns com.repldriven.queenswood.payee-check.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def ^:private store-name "payee-checks")

(def transact fdb/transact)

(defn save-check
  [txn check]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn store-name)
                      (schema/PayeeCheck->java check)))
   :payee-check/save
   "Failed to save payee check"))

(defn get-aggregates
  [txn bank-id checked-on]
  (fdb/transact
   txn
   (fn [txn]
     {:count (fdb/count-records-snapshot (fdb/open txn store-name)
                                         "PayeeCheck_count_by_bank_checked_on"
                                         [bank-id checked-on])})
   :payee-check/get-aggregates
   {:message "Failed to count payee checks"
    :bank-id bank-id
    :checked-on checked-on}))

(defn get-check
  [txn bank-id check-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn store-name) bank-id check-id)
             schema/pb->PayeeCheck))
   :payee-check/get
   "Failed to load payee check"))

(defn get-checks
  ([txn bank-id]
   (get-checks txn bank-id nil))
  ([txn bank-id opts]
   (fdb/transact
    txn
    (fn [txn]
      (let [{:keys [after before limit order]
             :or {limit 20 order :desc}}
            opts
            result (fdb/scan-records
                    (fdb/open txn store-name)
                    {:prefix [bank-id]
                     :after after
                     :before before
                     :limit limit
                     :order order})]
        {:items (mapv schema/pb->PayeeCheck (:records result))
         :before (:before result)
         :after (:after result)}))
    :payee-check/list
    "Failed to list payee checks")))
