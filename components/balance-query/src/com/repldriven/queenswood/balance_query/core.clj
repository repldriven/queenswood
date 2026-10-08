(ns com.repldriven.queenswood.balance-query.core
  (:require
    [com.repldriven.queenswood.balance-query.domain :as domain]
    [com.repldriven.queenswood.balance-query.store :as store]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(defn totals
  [balances currency]
  {:balances balances
   :posted-balance (domain/posted-balance balances currency)
   :available-balance (domain/available-balance balances currency)})

(defn get-balances
  [txn bank-id account-id currency]
  (let-nom>
    [result (store/get-balances txn bank-id account-id)]
    (totals result currency)))
