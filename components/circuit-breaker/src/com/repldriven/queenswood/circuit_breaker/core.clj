(ns com.repldriven.queenswood.circuit-breaker.core
  (:require
    [com.repldriven.queenswood.circuit-breaker.domain :as domain]
    [com.repldriven.queenswood.circuit-breaker.store :as store]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(defn allow
  [config policy destination now claimant]
  (store/transact
   config
   (fn [txn]
     (let-nom> [breaker (store/load-breaker txn destination)
                [decision changed] (domain/allow breaker
                                                 now
                                                 claimant
                                                 (:probe-lease-ms policy))
                _ (when changed (store/save-breaker txn changed))]
       decision))
   :circuit-breaker/allow
   "Failed to ask a circuit breaker"))

(defn record
  [config policy destination outcome now]
  (store/transact
   config
   (fn [txn]
     (let-nom> [breaker (store/load-breaker txn destination)
                changed (domain/record breaker destination outcome now policy)
                _ (when changed (store/save-breaker txn changed))]
       (or changed breaker)))
   :circuit-breaker/record
   "Failed to record a call on a circuit breaker"))

(defn breaker
  [config destination]
  (store/load-breaker config destination))
