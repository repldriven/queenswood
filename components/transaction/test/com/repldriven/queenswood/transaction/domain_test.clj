(ns com.repldriven.queenswood.transaction.domain-test
  "Pure-function tests for the leg validation that record /
  record-transaction run before persisting. Pins the double-entry
  invariant (debits = credits over every leg) and the
  positive-amount guard."
  (:require
    [com.repldriven.queenswood.transaction.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(defn- debit
  [amount]
  {:side :leg-side-debit :amount amount})

(defn- credit
  [amount]
  {:side :leg-side-credit :amount amount})

(deftest validate-legs-test
  (testing "a balanced two-leg posting passes"
    (is (nil? (SUT/validate-legs [(debit 1000) (credit 1000)]))))
  (testing "a balanced multi-leg posting passes"
    (is (nil? (SUT/validate-legs [(debit 500) (debit 500) (credit 300)
                                  (credit 700)]))))
  (testing "unbalanced debits and credits are rejected"
    (let [result (SUT/validate-legs [(debit 1000) (credit 999)])]
      (is (error/rejection? result))
      (is (= :transaction/legs-unbalanced (error/kind result)))))
  (testing "a non-positive amount is rejected before the balance check"
    (let [result (SUT/validate-legs [(debit 1000) (credit 0)])]
      (is (error/rejection? result))
      (is (= :transaction/invalid-amount (error/kind result)))))
  (testing "every leg counts toward the balance"
    (let [result (SUT/validate-legs [(debit 1000) (credit 1000) (credit 1000)])]
      (is (error/rejection? result))
      (is (= :transaction/legs-unbalanced (error/kind result))))))

(defn- of-type
  [transaction-type]
  {:bank-id "bnk.test"
   :transaction-type transaction-type
   :currency "GBP"})

(deftest new-transaction-bank-id-test
  (testing "the bank the key is scoped by is carried onto the transaction"
    (is (= "bnk.test"
           (:bank-id (SUT/new-transaction
                      (of-type :transaction-type-internal-transfer))))))
  (testing "data with no bank is rejected"
    (let [result (SUT/new-transaction
                  (dissoc (of-type :transaction-type-internal-transfer)
                   :bank-id))]
      (is (error/rejection? result))
      (is (= :transaction/missing-bank-id (error/kind result))))))
