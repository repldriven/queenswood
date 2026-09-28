(ns com.repldriven.queenswood.payment.domain.internal-test
  (:require
    [com.repldriven.queenswood.payment.domain.fixtures :as fixtures]
    [com.repldriven.queenswood.payment.domain.internal :as SUT]

    [clojure.test :refer [deftest is testing]]))

(deftest internal-payment->transaction-test
  (let [tx (SUT/internal-payment->transaction
            {:idempotency-key "idem-1"
             :debtor-account-id "debtor"
             :creditor-account-id "creditor"
             :currency "GBP"
             :amount 500
             :reference "Test"}
            (fixtures/account "debtor" "GBP")
            (fixtures/account "creditor" "GBP")
            (fixtures/allow-all)
            (fixtures/empty-aggregates :internal-payment))]
    (testing "envelope carries idempotency-key, type, currency, reference"
      (is (= "idem-1" (:idempotency-key tx)))
      (is (= :transaction-type-internal-transfer (:transaction-type tx)))
      (is (= "GBP" (:currency tx)))
      (is (= "Test" (:reference tx))))
    (testing "two legs, both on :balance-type-default / :posted"
      (is (= 2 (count (:legs tx))))
      (is (every? (fn [leg]
                    (and (= :balance-type-default (:balance-type leg))
                         (= :balance-status-posted (:balance-status leg))))
                  (:legs tx))))
    (testing "debtor debited, creditor credited, both for `amount`"
      (is (= {:account-id "debtor" :amount 500}
             (select-keys (fixtures/side tx :leg-side-debit)
                          [:account-id :amount])))
      (is (= {:account-id "creditor" :amount 500}
             (select-keys (fixtures/side tx :leg-side-credit)
                          [:account-id :amount]))))))
