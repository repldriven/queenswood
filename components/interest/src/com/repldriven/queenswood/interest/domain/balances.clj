(ns com.repldriven.queenswood.interest.domain.balances
  (:require
    [com.repldriven.queenswood.balance-query.interface :as balance-query]))

(defn- bucket
  [balances balance-type currency balance-status]
  (first (filter (fn [b]
                   (and (= balance-type (:balance-type b))
                        (= currency (:currency b))
                        (= balance-status (:balance-status b))))
                 balances)))

(defn- net
  [balance]
  (- (:credit balance 0) (:debit balance 0)))

(defn accrued-interest-balance
  "The accrued interest balance for `currency`,
  or nil when not found"
  [balances currency]
  (bucket balances
          :balance-type-interest-accrued
          currency
          :balance-status-posted))

(defn accrued-amount
  "The accrued interest balance amount for `currency`,
  or zero when not found"
  [balances currency]
  (net (accrued-interest-balance balances currency)))

(defn principal-amount
  "The principal amount for calculating accrued interest for `currency`,
  or zero when not found"
  [balances currency]
  (:value (balance-query/available-balance balances currency)))
