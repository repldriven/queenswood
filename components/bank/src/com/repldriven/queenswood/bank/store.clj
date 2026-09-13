(ns com.repldriven.queenswood.bank.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

;; must match bank-bank-query.store/store-name — same FDB store
(def ^:private store-name "banks")

(def transact fdb/transact)

(defn allocate-sort-code
  "Allocate the next sort code from a global monotonic fountain, formatted
  as a 6-digit string (000001, 000002, ...). `00`-prefixed sort codes are
  unallocated in the real world, so the range is safe."
  [txn]
  (fdb/transact txn
                (fn [txn]
                  (format "%06d"
                          (fdb/allocate-counter txn
                                                store-name
                                                "bank"
                                                "sort-codes")))
                :bank/allocate-sort-code
                "Failed to allocate sort code"))

(defn count-creations
  [txn principal-id idempotency-key]
  (fdb/transact txn
                (fn [txn]
                  (fdb/allocate-counter txn
                                        store-name
                                        "bank"
                                        "creations"
                                        principal-id
                                        idempotency-key))
                :bank/count-creations
                "Failed to count bank creations"))

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
