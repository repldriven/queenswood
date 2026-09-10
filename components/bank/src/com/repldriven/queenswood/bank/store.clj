(ns com.repldriven.queenswood.bank.store
  (:require
    [com.repldriven.queenswood.bank.changelog :as changelog]

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

(defn create
  [txn bank]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn store-name)
                                   (schema/Bank->java bank)))
                :bank/create
                "Failed to create bank"))

(defn- save-with
  "Persist `bank` and append the entry `entry-fn` builds from
  `changelog`, in one transaction — the record change and the event
  that announces it cannot diverge."
  [txn bank entry-fn changelog]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn store-name)]
       (let-nom>
         [_ (fdb/save-record store (schema/Bank->java bank))
          entry (entry-fn changelog)
          _ (fdb/write-changelog txn
                                 store-name
                                 (:bank-id bank)
                                 entry)]
         bank)))
   :bank/save
   "Failed to save bank"))

(defn save-status
  [txn bank changelog]
  (save-with txn bank changelog/status-changed changelog))

(defn save-tier
  [txn bank changelog]
  (save-with txn bank changelog/tier-changed changelog))
