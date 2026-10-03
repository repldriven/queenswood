(ns com.repldriven.queenswood.circuit-breaker.core
  (:require
    [com.repldriven.queenswood.circuit-breaker.domain :as domain]
    [com.repldriven.queenswood.circuit-breaker.store :as store]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

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

(defn guard
  [config policy destination outcome-of f]
  (let [decision (allow config
                        policy
                        destination
                        (utility/now)
                        (str (utility/uuidv7)))]
    (if (= :open decision)
      (error/fail :circuit-breaker/open
                  {:message "The destination's circuit breaker is open"
                   :destination destination})
      (let [res (f)
            recorded (record config
                             policy
                             destination
                             (outcome-of res)
                             (utility/now))]
        (when (error/anomaly? recorded)
          (log/error "Circuit breaker not recorded"
                     {:destination destination :anomaly recorded}))
        res))))
