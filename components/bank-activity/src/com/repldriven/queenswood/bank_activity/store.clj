(ns com.repldriven.queenswood.bank-activity.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]))

(defn write-entry
  [txn log-name record-id entry]
  (fdb/write-log txn log-name record-id entry))
