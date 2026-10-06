(ns com.repldriven.queenswood.interest.domain.capitalization-test
  "What one account's sweep settles on, and the double entry behind it
  that moves the customer's balance and interest payable together."
  (:require
    [com.repldriven.queenswood.interest.domain.capitalization :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private accrued-balances
  [{:product-type :product-type-sub-ledger-current
    :balance-type :balance-type-default
    :balance-status :balance-status-posted
    :currency "GBP"
    :credit 2000
    :debit 0}
   {:product-type :product-type-sub-ledger-current
    :balance-type :balance-type-interest-accrued
    :balance-status :balance-status-posted
    :currency "GBP"
    :credit 110
    :debit 0}])

(def ^:private nothing-accrued (vec (take 1 accrued-balances)))

(def ^:private account
  {:account-id "acc.1" :product-type :product-type-sub-ledger-current})

(deftest sweep-test
  (testing "nothing accrued means nothing to sweep, and that is not a failure"
    (is (nil? (SUT/sweep "org.1"
                         account
                         "GBP"
                         "led.payable"
                         nothing-accrued
                         20260501))))
  (testing "an accrued balance that nets to zero is nothing to sweep either"
    (let [zeroed (conj nothing-accrued
                       {:product-type :product-type-sub-ledger-current
                        :balance-type :balance-type-interest-accrued
                        :balance-status :balance-status-posted
                        :currency "GBP"
                        :credit 100
                        :debit 100})]
      (is (nil?
           (SUT/sweep "org.1" account "GBP" "led.payable" zeroed 20260501)))))
  (testing "a sweep takes the whole accrued balance"
    (let [swept (SUT/sweep "org.1"
                           account
                           "GBP"
                           "led.payable"
                           accrued-balances
                           20260501)
          {:keys [transaction amount principal]} swept
          legs (:legs transaction)]
      (is (= "org.1" (:bank-id transaction)))
      (is (= "capitalize-acc.1-20260501" (:idempotency-key transaction)))
      (is (= :transaction-type-interest-capital
             (:transaction-type transaction)))
      (is (= 2 (count legs)))
      (testing "amount and input are the same figure — a sweep takes it all"
        (is (= 110 amount))
        (is (= 110 principal)))
      (testing "every leg uses the accrued amount, on posted"
        (is (every? (fn [leg] (= 110 (:amount leg))) legs))
        (is (every? (fn [leg] (= :balance-status-posted (:balance-status leg)))
                    legs)))
      (testing "DR interest payable, CR the customer's default balance"
        (let [[debit credit] legs]
          (is (= "led.payable" (:account-id debit)))
          (is (= :balance-type-default (:balance-type debit)))
          (is (= :leg-side-debit (:side debit)))
          (is (= "acc.1" (:account-id credit)))
          (is
           (= :product-type-sub-ledger-current (:product-type credit))
           "the credit carries the account's product type, which its
               control sums its legs by")
          (is (= :balance-type-default (:balance-type credit)))
          (is (= :leg-side-credit (:side credit)))))
      (testing "the legs applied also empty the customer's accrued balance"
        (is (= (conj legs
                     {:account-id "acc.1"
                      :balance-type :balance-type-interest-accrued
                      :balance-status :balance-status-posted
                      :side :leg-side-debit
                      :amount 110
                      :currency "GBP"})
               (:legs swept))))
      (testing "legs balance — Σdebit == Σcredit"
        (let [total-for (fn [side]
                          (transduce (comp (filter (fn [l] (= side (:side l))))
                                           (map :amount))
                                     +
                                     legs))]
          (is (= (total-for :leg-side-debit) (total-for :leg-side-credit)))))))
  (testing "the key composes account and date, so a repeat posts once"
    (let [key-for (fn [account-id]
                    (get-in (SUT/sweep "org.1"
                                       (assoc account :account-id account-id)
                                       "GBP"
                                       "led.payable"
                                       accrued-balances
                                       20260501)
                            [:transaction :idempotency-key]))]
      (is (not= (key-for "acc.1") (key-for "acc.2")))
      (is (= (key-for "acc.1") (key-for "acc.1"))))))
