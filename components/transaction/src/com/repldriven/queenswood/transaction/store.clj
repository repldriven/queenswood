(ns com.repldriven.queenswood.transaction.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]))

(def ^:private store-name "transactions")
(def ^:private legs-store-name "transaction-legs")

(def transact fdb/transact)
(def uniqueness-violation? fdb/uniqueness-violation?)

(defn save-transaction
  [txn transaction]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn store-name)
                      (schema/Transaction->java transaction)))
   :transaction/save
   "Failed to save transaction"))

(defn save-legs
  [txn legs]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn legs-store-name)]
       (reduce (fn [_ leg]
                 (let [result (fdb/save-record
                               store
                               (schema/TransactionLeg->java leg))]
                   (when (error/anomaly? result)
                     (reduced result))))
               nil
               legs)))
   :transaction/save-legs
   "Failed to save transaction legs"))

(defn find-transaction-by-idempotency-key
  [txn bank-id transaction-type idempotency-key]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/query-record-compound
              (fdb/open txn store-name)
              "Transaction"
              [["bank_id" bank-id]
               ["transaction_type"
                (schema/transaction-type->pb-enum transaction-type)]
               ["idempotency_key" idempotency-key]]
              {:index "Transaction_by_idempotency_key"})
             schema/pb->Transaction))
   :transaction/find-by-idempotency-key
   "Failed to find transaction by idempotency key"))

(defn get-transactions
  ([txn account-id]
   (get-transactions txn account-id nil))
  ([txn account-id opts]
   (fdb/transact
    txn
    (fn [txn]
      (let [{:keys [limit order] :or {limit 1000 order :desc}} opts
            leg-store (fdb/open txn legs-store-name)
            txn-store (fdb/open txn store-name)
            legs (mapv schema/pb->TransactionLeg
                       (:records (fdb/scan-records
                                  leg-store
                                  {:prefix [account-id]
                                   :limit limit
                                   :order order})))]
        (mapv (fn [leg]
                (let [txn-record (fdb/load-record txn-store
                                                  (:transaction-id leg))
                      parent (when txn-record
                               (schema/pb->Transaction txn-record))]
                  (merge leg
                         (select-keys parent
                                      [:transaction-type
                                       :status
                                       :reference]))))
              legs)))
    :transaction/list
    "Failed to list account transactions")))
