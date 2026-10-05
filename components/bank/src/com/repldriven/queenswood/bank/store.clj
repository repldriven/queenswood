(ns com.repldriven.queenswood.bank.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

;; must match bank-bank-query.store/store-name — same FDB store
(def ^:private store-name "banks")

(def transact fdb/transact)

(def uniqueness-violation? fdb/uniqueness-violation?)

(defn find-by-creation
  [txn principal-id idempotency-key]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/query-record-compound
                           (fdb/open txn store-name)
                           "Bank"
                           [[["created_by" "principal_id"] principal-id]
                            ["idempotency_key" idempotency-key]]
                           {:index "Bank_by_creator_idempotency_key"})
                          schema/pb->Bank))
                :bank/find-by-creation
                "Failed to find bank by creation"))

(defn create
  [txn bank]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn store-name)
                                   (schema/Bank->java bank)))
                :bank/create
                "Failed to create bank"))

(defn save
  [txn bank entry]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn store-name)]
       (let-nom>
         [_ (fdb/save-record store (schema/Bank->java bank))
          _ (fdb/write-changelog txn
                                 store-name
                                 (:bank-id bank)
                                 entry)]
         bank)))
   :bank/save
   "Failed to save bank"))
