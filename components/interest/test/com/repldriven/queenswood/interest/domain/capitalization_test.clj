(ns com.repldriven.queenswood.interest.domain.capitalization-test
  "What one account's sweep settles on, the customer-facing double
  entry behind it, and the bank's side of a run."
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

(deftest sweep-test
  (testing "nothing accrued means nothing to sweep, and that is not a failure"
    (is (nil? (SUT/sweep "org.1" "acc.1" "GBP" nothing-accrued 20260501))))
  (testing "an accrued balance that nets to zero is nothing to sweep either"
    (let [zeroed (conj nothing-accrued
                       {:product-type :product-type-sub-ledger-current
                        :balance-type :balance-type-interest-accrued
                        :balance-status :balance-status-posted
                        :currency "GBP"
                        :credit 100
                        :debit 100})]
      (is (nil? (SUT/sweep "org.1" "acc.1" "GBP" zeroed 20260501)))))
  (testing "a sweep takes the whole accrued balance"
    (let [{:keys [transaction amount principal]}
          (SUT/sweep "org.1" "acc.1" "GBP" accrued-balances 20260501)
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
      (testing "DR the customer's accrued balance, CR their default one"
        (let [[debit credit] legs]
          (is (= "acc.1" (:account-id debit)))
          (is (= :balance-type-interest-accrued (:balance-type debit)))
          (is (= :leg-side-debit (:side debit)))
          (is (= "acc.1" (:account-id credit)))
          (is (= :balance-type-default (:balance-type credit)))
          (is (= :leg-side-credit (:side credit)))))
      (testing "legs balance — Σdebit == Σcredit"
        (let [total-for (fn [side]
                          (transduce (comp (filter (fn [l] (= side (:side l))))
                                           (map :amount))
                                     +
                                     legs))]
          (is (= (total-for :leg-side-debit) (total-for :leg-side-credit)))))))
  (testing "the key composes account and date, so a repeat posts once"
    (let [key-for
          (fn [account-id]
            (get-in
             (SUT/sweep "org.1" account-id "GBP" accrued-balances 20260501)
             [:transaction :idempotency-key]))]
      (is (not= (key-for "acc.1") (key-for "acc.2")))
      (is (= (key-for "acc.1") (key-for "acc.1"))))))

(deftest entries-test
  (testing "one entry per currency and product type"
    (let [groups #{["GBP" :product-type-sub-ledger-current]
                   ["GBP" :product-type-sub-ledger-savings]}]
      (is (= groups (SUT/entries groups))))))

(def ^:private gl
  {:payable "led.payable"
   :controls {:product-type-sub-ledger-current "led.current"
              :product-type-sub-ledger-savings "led.savings"}})

(deftest ledger-transaction-test
  (testing "a group that swept nothing posts nothing"
    (is (nil? (SUT/ledger-transaction gl
                                      "org.1"
                                      "GBP" :product-type-sub-ledger-current
                                      0 20260501))))
  (testing "DR interest payable, CR the product's deposit control"
    (let [tx (SUT/ledger-transaction gl
                                     "org.1"
                                     "GBP" :product-type-sub-ledger-current
                                     5000 20260501)
          [debit credit] (:legs tx)]
      (is (= "led.payable" (:account-id debit)))
      (is (= :leg-side-debit (:side debit)))
      (is (= "led.current" (:account-id credit)))
      (is (= :leg-side-credit (:side credit)))
      (testing "both legs carry the group's total, so the entry balances"
        (is (= 5000 (:amount debit)))
        (is (= 5000 (:amount credit))))))
  (testing "each product type credits its own control"
    (let [credit-for (fn [product-type]
                       (:account-id (second (:legs (SUT/ledger-transaction
                                                    gl
                                                    "org.1"
                                                    "GBP" product-type
                                                    5000 20260501)))))]
      (is (= "led.current" (credit-for :product-type-sub-ledger-current)))
      (is (= "led.savings" (credit-for :product-type-sub-ledger-savings)))))
  (testing "the key separates product types, so each group posts once"
    ;; A shared key would let the first group posted swallow the rest
    ;; as duplicates.
    (let [key-for (fn [currency product-type]
                    (:idempotency-key (SUT/ledger-transaction gl
                                                              "org.1"
                                                              currency
                                                              product-type
                                                              5000
                                                              20260501)))]
      (is (not= (key-for "GBP" :product-type-sub-ledger-current)
                (key-for "GBP" :product-type-sub-ledger-savings)))
      (testing "currency separates groups too, for a multi-currency bank"
        (is (not= (key-for "GBP" :product-type-sub-ledger-current)
                  (key-for "EUR" :product-type-sub-ledger-current))))
      (is (= (key-for "GBP" :product-type-sub-ledger-current)
             (key-for "GBP" :product-type-sub-ledger-current))))))
