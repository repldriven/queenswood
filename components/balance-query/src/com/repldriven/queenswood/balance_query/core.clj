(ns com.repldriven.queenswood.balance-query.core
  (:require
    [com.repldriven.queenswood.balance-query.store :as store]

    [com.repldriven.queenswood.balance-domain.interface :as domain]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(defn totals
  [balances]
  (let [currency (:currency (first balances) "")]
    {:balances balances
     :posted-balance (domain/posted-balance balances currency)
     :available-balance (domain/available-balance balances currency)}))

(defn get-balances
  [txn bank-id account-id]
  (let-nom>
    [result (store/get-balances txn bank-id account-id)]
    (totals result)))
