(ns com.repldriven.queenswood.party-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

;; must match bank-party.store/store-name — same FDB store
(def ^:private store-name "parties")
;; must match bank-party.store/party-national-identifiers-store-name
(def ^:private party-national-identifiers-store-name
  "party-national-identifiers")

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

(defn get-party-national-identifier
  [txn party-id]
  (fdb/transact
   txn
   (fn [txn]
     ;; The store's primary key is compound [party-id type] — a party
     ;; can hold one identifier per type — so scan the party-id prefix
     ;; and take the first rather than load-record by an exact key.
     (let [result (fdb/scan-records
                   (fdb/open txn party-national-identifiers-store-name)
                   {:prefix [party-id] :limit 1})]
       (some-> (first (:records result))
               schema/pb->PartyNationalIdentifier)))
   :party/get-party-national-identifier
   "Failed to load party national identifier"))

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
