(ns com.repldriven.queenswood.person-identification.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def ^:private store-name "person-names")

;; What was kept before ADR-0045: its date of birth, nationality and
;; address are required, so the clearance moves the names to
;; `person-names` and deletes the record rather than clearing it.
(def transact fdb/transact)

(defn save-person-identification
  [txn person-identification]
  (fdb/transact
   txn
   (fn [txn]
     (fdb/save-record (fdb/open txn store-name)
                      (schema/PersonName->java person-identification)))
   :person-identification/save
   "Failed to save person identification"))

(defn get-person-identification
  [txn party-id]
  (fdb/transact
   txn
   (fn [txn]
     (some-> (fdb/load-record (fdb/open txn store-name) party-id)
             schema/pb->PersonName))
   :person-identification/get
   "Failed to load person identification"))
