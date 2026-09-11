(ns com.repldriven.queenswood.fdb.check
  (:require
    [com.repldriven.queenswood.fdb.transact]

    [com.repldriven.mono.error.interface :as error])
  (:import
    (com.apple.foundationdb.record RecordIndexUniquenessViolation)
    (com.repldriven.queenswood.fdb.transact Txn)))

(defn txn?
  [x]
  (instance? Txn x))

(defn uniqueness-violation?
  [anomaly]
  (when (error/anomaly? anomaly)
    (loop [ex (:exception (error/payload anomaly))]
      (cond
       (nil? ex)
       false

       (instance? RecordIndexUniquenessViolation ex)
       true

       :else
       (recur (.getCause ex))))))
