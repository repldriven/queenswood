(ns com.repldriven.queenswood.transaction.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]))

(def ^:private store-name "transactions")
(def ^:private legs-store-name "transaction-legs")

(def ^:private leg-sum-index "TransactionLeg_sum_amount_by_account_bucket_side")

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

(defn save-transaction-and-legs
  [txn transaction legs]
  (fdb/transact
   txn
   (fn [txn]
     (let [legs-store (fdb/open txn legs-store-name)]
       (fdb/save-records
        (into [[(fdb/open txn store-name)
                (schema/Transaction->java transaction)]]
              (map (fn [leg] [legs-store (schema/TransactionLeg->java leg)]))
              legs))))
   :transaction/save
   "Failed to save transaction"))

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

(defn page-transactions
  [txn account-id opts]
  (fdb/transact
   txn
   (fn [txn]
     (let [{:keys [after before limit order] :or {limit 1000 order :desc}} opts
           leg-store (fdb/open txn legs-store-name)
           txn-store (fdb/open txn store-name)
           result (fdb/scan-records leg-store
                                    {:prefix [account-id]
                                     :after after
                                     :before before
                                     :limit limit
                                     :order order})]
       {:transactions
        (mapv (fn [leg]
                (let [txn-record (fdb/load-record txn-store
                                                  (:transaction-id leg))
                      parent (when txn-record
                               (schema/pb->Transaction txn-record))]
                  (merge leg
                         (select-keys parent
                                      [:transaction-type :status :reference]))))
              (map schema/pb->TransactionLeg (:records result)))
        :before (:before result)
        :after (:after result)}))
   :transaction/list
   "Failed to list account transactions"))

(defn get-transactions
  ([txn account-id]
   (get-transactions txn account-id nil))
  ([txn account-id opts]
   (error/let-nom> [{:keys [transactions]}
                    (page-transactions txn account-id opts)]
     transactions)))

(defn sum-legs
  [txn account-id balance-type balance-status isolation]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn legs-store-name)
           sum (if (= :serializable isolation)
                 fdb/sum-records
                 fdb/sum-records-snapshot)
           group (fn [side]
                   [account-id
                    (schema/balance-type->int balance-type)
                    (schema/balance-status->int balance-status)
                    (schema/leg-side->int side)])]
       {:credit (sum store leg-sum-index (group :leg-side-credit))
        :debit (sum store leg-sum-index (group :leg-side-debit))}))
   :transaction/sum-legs
   "Failed to sum transaction legs"))
