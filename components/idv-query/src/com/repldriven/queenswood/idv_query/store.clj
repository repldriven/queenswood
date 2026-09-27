(ns com.repldriven.queenswood.idv-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

;; must match idv.store — the same FDB stores
(def ^:private idvs-store-name "idvs")

(def ^:private sessions-store-name "idv-sessions")

(defn get-idv
  [txn bank-id verification-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn idvs-store-name)
                                           bank-id
                                           verification-id)
                          schema/pb->Idv))
                :idv/get
                "Failed to load IDV"))

(defn get-idv-by-party
  [txn party-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/query-record (fdb/open txn idvs-store-name)
                                            "Idv"
                                            "party_id"
                                            party-id
                                            {:index "Idv_by_party"})
                          schema/pb->Idv))
                :idv/get-by-party
                "Failed to look up IDV by party"))

(defn get-session
  [txn bank-id session-id]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn sessions-store-name)
                                           bank-id
                                           session-id)
                          schema/pb->IdvSession))
                :idv/get-session
                "Failed to load IDV session"))

(defn get-sessions-by-verification
  [txn bank-id verification-id]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->IdvSession
                        (fdb/query-records-compound
                         (fdb/open txn sessions-store-name)
                         "IdvSession"
                         [["bank_id" bank-id]
                          ["verification_id" verification-id]]
                         {:index "IdvSession_by_verification"})))
                :idv/get-sessions
                "Failed to load IDV sessions"))

(defn count-sessions-on
  [txn bank-id day]
  (fdb/transact txn
                (fn [txn]
                  (fdb/count-records-snapshot (fdb/open txn
                                                        sessions-store-name)
                                              "IdvSession_count_by_bank_day"
                                              [bank-id day]))
                :idv/count-sessions
                "Failed to count IDV sessions"))
