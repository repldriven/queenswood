(ns com.repldriven.queenswood.interest.domain.balances-test
  "The readings a pass takes off an account: what has accrued, what
  remainder it carries, and what a day's interest is earned on."
  (:require
    [com.repldriven.queenswood.interest.domain.balances :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private account-balances
  [{:product-type :product-type-sub-ledger-current
    :balance-type :balance-type-default
    :balance-status :balance-status-posted
    :currency "GBP"
    :credit 2000
    :debit 0}
   {:product-type :product-type-sub-ledger-current
    :balance-type :balance-type-default
    :balance-status :balance-status-pending-outgoing
    :currency "GBP"
    :credit 0
    :debit 500}
   {:product-type :product-type-sub-ledger-current
    :balance-type :balance-type-interest-accrued
    :balance-status :balance-status-posted
    :currency "GBP"
    :credit 40
    :debit 0}])

(deftest accrued-interest-balance-test
  (testing "the accrued balance is found by currency alone"
    (let [b (SUT/accrued-interest-balance account-balances "GBP")]
      (is (= :balance-type-interest-accrued (:balance-type b)))
      (is (= 40 (:credit b)))))
  (testing "a currency the account does not hold is nil, not a zeroed stand-in"
    ;; Nil is the caller's cue to say something. A zeroed balance would
    ;; read as a real reading of nothing and accrue silently against a
    ;; figure nobody wrote.
    (is (nil? (SUT/accrued-interest-balance account-balances "USD"))))
  (testing "an in-flight balance is not the accrued one"
    (is (nil? (SUT/accrued-interest-balance
               [{:balance-type :balance-type-interest-accrued
                 :balance-status :balance-status-pending-outgoing
                 :currency "GBP"
                 :credit 40}]
               "GBP")))))

(deftest accrued-amount-test
  (testing "what has accrued is credit less debit on that balance"
    (is (= 40 (SUT/accrued-amount account-balances "GBP"))))
  (testing "no accrued balance at all reads as zero"
    (is (= 0 (SUT/accrued-amount account-balances "USD"))))
  (testing "a swept balance nets back to zero"
    (is (= 0
           (SUT/accrued-amount [{:balance-type :balance-type-interest-accrued
                                 :balance-status :balance-status-posted
                                 :currency "GBP"
                                 :credit 100
                                 :debit 100}]
                               "GBP")))))

(deftest principal-amount-test
  (testing "interest is earned on the available balance, not the posted one"
    ;; 2000 posted less a 500 outgoing reservation.
    (is (= 1500 (SUT/principal-amount account-balances "GBP"))))
  (testing "money still pending inbound has not arrived, so it does not earn"
    (is (= 2000
           (SUT/principal-amount
            [{:product-type :product-type-sub-ledger-current
              :balance-type :balance-type-default
              :balance-status :balance-status-posted
              :currency "GBP"
              :credit 2000
              :debit 0}
             {:product-type :product-type-sub-ledger-current
              :balance-type :balance-type-default
              :balance-status :balance-status-pending-incoming
              :currency "GBP"
              :credit 900
              :debit 0}]
            "GBP"))))
  (testing "the accrued balance is not itself principal — interest is simple"
    ;; 40 has accrued on top of the 1500 available, and earns nothing
    ;; until it is capitalised into the default balance.
    (is (= 1500 (SUT/principal-amount account-balances "GBP")))))
