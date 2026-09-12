(ns com.repldriven.queenswood.api.balance.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def BalanceNotFound
  {:value {:title "REJECTED"
           :type "balances/not-found"
           :status 404
           :detail "Balance not found"}})

(def registry (examples-registry [#'BalanceNotFound]))

(def Balance
  {:account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :product-type :current
   :balance-type :default
   :balance-status :posted
   :currency "GBP"
   :credit 0
   :debit 0
   :created-at "2025-01-01T00:00:00Z"
   :updated-at "2025-01-01T00:00:00Z"})

(def BalanceList
  {:balances [Balance]
   :posted-balance {:value 0 :currency "GBP"}
   :available-balance {:value 0 :currency "GBP"}})

(def BalanceProduct {:balance-type :default :balance-status :posted})
