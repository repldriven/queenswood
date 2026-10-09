(ns com.repldriven.queenswood.party-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

;; must match bank-party.store/store-name — same FDB store
(def ^:private store-name "parties")

(def transact fdb/transact)

(defn get-party
  [txn bank-id party-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn store-name) bank-id party-id)
             schema/pb->Party))
   :party/get
   "Failed to load party"))

(defn get-party-by-id
  [txn party-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record (fdb/open txn store-name)
                               "Party"
                               "party_id"
                               party-id
                               {:index "Party_by_party_id"})
             schema/pb->Party))
   :party/get-by-id
   "Failed to load party by id"))

(defn find-party-by-idempotency-key
  [txn bank-id idempotency-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record-compound
              (fdb/open txn store-name)
              "Party"
              [["bank_id" bank-id]
               ["idempotency_key" idempotency-key]]
              {:index "Party_by_idempotency_key"})
             schema/pb->Party))
   :party/find-by-idempotency-key
   "Failed to find party by idempotency key"))

(defn get-parties
  ([txn bank-id]
   (get-parties txn bank-id nil))
  ([txn bank-id opts]
   (fdb/transact
    txn
    (fn [txn]
      (let [{:keys [after before limit order]
             :or {limit 100 order :desc}}
            opts
            result (fdb/scan-records
                    (fdb/open txn store-name)
                    {:prefix [bank-id]
                     :after after
                     :before before
                     :limit limit
                     :order order})]
        {:parties (mapv schema/pb->Party (:records result))
         :before (:before result)
         :after (:after result)}))
    :party/list
    "Failed to list parties")))
