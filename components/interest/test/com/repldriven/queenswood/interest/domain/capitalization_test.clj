(ns com.repldriven.queenswood.interest.domain.capitalization-test
  "What one account's sweep settles on, and the double entry behind it
  that moves the account's interest from its accrued bucket to its
  spendable one."
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
    (is (nil? (SUT/sweep "org.1" account "GBP" nothing-accrued 20260501))))
  (testing "an accrued balance that nets to zero is nothing to sweep either"
    (let [zeroed (conj nothing-accrued
                       {:product-type :product-type-sub-ledger-current
                        :balance-type :balance-type-interest-accrued
                        :balance-status :balance-status-posted
                        :currency "GBP"
                        :credit 100
                        :debit 100})]
      (is (nil? (SUT/sweep "org.1" account "GBP" zeroed 20260501)))))
  (testing "a sweep takes the whole accrued balance"
    (let [{:keys [transaction amount principal]}
          (SUT/sweep "org.1" account "GBP" accrued-balances 20260501)
          legs (:legs transaction)]
      (is (= "org.1" (:bank-id transaction)))
      (is (= "capitalize-acc.1-20260501" (:idempotency-key transaction)))
      (is (= :transaction-type-interest-capitalization
             (:transaction-type transaction)))
      (testing "amount and input are the same figure — a sweep takes it all"
        (is (= 110 amount))
        (is (= 110 principal)))
      (testing "DR the account's interest accrued, CR its default balance"
        (is (= [["acc.1" :balance-type-interest-accrued :leg-side-debit 110]
                ["acc.1" :balance-type-default :leg-side-credit 110]]
               (mapv (juxt :account-id :balance-type :side :amount) legs))))
      (testing "both legs carry the product type their controls sum by"
        (is (every? (fn [leg]
                      (= :product-type-sub-ledger-current (:product-type leg)))
                    legs)))))
  (testing "an overdrawn principal's charge is swept the other way"
    (let [charged (conj nothing-accrued
                        {:product-type :product-type-sub-ledger-current
                         :balance-type :balance-type-interest-accrued
                         :balance-status :balance-status-posted
                         :currency "GBP"
                         :credit 0
                         :debit 7})]
      (is (= [[:balance-type-interest-accrued :leg-side-credit 7]
              [:balance-type-default :leg-side-debit 7]]
             (mapv (juxt :balance-type :side :amount)
                   (get-in (SUT/sweep "org.1" account "GBP" charged 20260501)
                           [:transaction :legs]))))))
  (testing "the key composes account and date, so a repeat posts once"
    (let [key-for (fn [account-id]
                    (get-in (SUT/sweep "org.1"
                                       (assoc account :account-id account-id)
                                       "GBP"
                                       accrued-balances
                                       20260501)
                            [:transaction :idempotency-key]))]
      (is (not= (key-for "acc.1") (key-for "acc.2")))
      (is (= (key-for "acc.1") (key-for "acc.1"))))))
