(ns com.repldriven.queenswood.interest.domain.balances
  (:require
    [com.repldriven.queenswood.balance-query.interface :as balance-query]))

(defn- bucket
  [balances balance-type balance-status]
  (first (filter (fn [b]
                   (and (= balance-type (:balance-type b))
                        (= balance-status (:balance-status b))))
                 balances)))

(defn- net
  [balance]
  (- (:credit balance 0) (:debit balance 0)))

(defn accrued-interest-balance
  "The account's accrued interest balance, or nil when not found"
  [balances]
  (bucket balances :balance-type-interest-accrued :balance-status-posted))

(defn accrued-amount
  "The account's accrued interest amount, or zero when not found"
  [balances]
  (net (accrued-interest-balance balances)))

(defn principal-amount
  "The principal amount for calculating accrued interest for `currency`,
  or zero when not found"
  [balances currency]
  (:value (balance-query/available-balance balances currency)))
