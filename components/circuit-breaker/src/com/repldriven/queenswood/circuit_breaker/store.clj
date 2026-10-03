(ns com.repldriven.queenswood.circuit-breaker.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]))

(def ^:private store-name "circuit-breakers")

(def transact fdb/transact)

(defn load-breaker
  [txn destination]
  (fdb/transact txn
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn store-name)
                                           destination)
                          schema/pb->CircuitBreaker))
                :circuit-breaker/load
                "Failed to load a circuit breaker"))

(defn save-breaker
  [txn breaker]
  (fdb/transact txn
                (fn [txn]
                  (fdb/save-record (fdb/open txn store-name)
                                   (schema/CircuitBreaker->java breaker)))
                :circuit-breaker/save
                "Failed to save a circuit breaker"))
