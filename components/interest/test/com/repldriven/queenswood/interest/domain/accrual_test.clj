(ns com.repldriven.queenswood.interest.domain.accrual-test
  "The outcome one account's accrual settles on, from its version's
  terms, and the transactions a chunk of them records."
  (:require
    [com.repldriven.queenswood.interest.domain.accrual :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private earning-balances
  "A current account holding 1500 available (2000 posted less a 500
  outgoing reservation) and an accrued balance."
  [{:product-type :account-product-type-sub-ledger-current
    :balance-type :balance-type-default
    :balance-status :balance-status-posted
    :currency "GBP"
    :credit 2000
    :debit 0}
   {:product-type :account-product-type-sub-ledger-current
    :balance-type :balance-type-default
    :balance-status :balance-status-pending-outgoing
    :currency "GBP"
    :credit 0
    :debit 500}
   {:product-type :account-product-type-sub-ledger-current
    :balance-type :balance-type-interest-accrued
    :balance-status :balance-status-posted
    :currency "GBP"
    :credit 40
    :debit 0}])

(def ^:private spendable-only (vec (take 2 earning-balances)))

(def ^:private account
  {:account-id "acc.1"
   :product-id "prd.1"
   :version-id "ver.1"
   :currency "GBP"
   :version-from-on 20089})

(def ^:private flat
  "A version's accrual at 100 bps on the whole balance from opening."
  {:banding :interest-banding-marginal
   :day-count :interest-day-count-actual-365
   :bands [0]
   :schedule {:relative {:unit :interest-period-unit-month
                         :periods [{:after 0 :rates-bps [100]}]}}})

(defn- day-rates
  [accrual business-day]
  (SUT/day-rates accrual (:version-from-on account) business-day))

(deftest day-rates-test
  (testing "a version paying no interest has none"
    (is (nil? (day-rates nil 20100))))
  (testing "the version's banding, day count and schedule are read"
    (is (= {:banding :whole-balance
            :bands [0 1000]
            :rates-bps [100 200]
            :basis 360}
           (day-rates {:banding :interest-banding-whole-balance
                       :day-count :interest-day-count-actual-360
                       :bands [0 1000]
                       :schedule {:fixed {:periods [{:from-on 20089
                                                     :rates-bps [100 200]}]}}}
                      20100))))
  (testing "a relative schedule counts from the day the account came on"
    (let [weekly (assoc flat
                        :schedule
                        {:relative {:unit :interest-period-unit-week
                                    :periods [{:after 0 :rates-bps [0]}
                                              {:after 1 :rates-bps [100]}]}})]
      (is (= [0] (:rates-bps (day-rates weekly 20095))))
      (is (= [100] (:rates-bps (day-rates weekly 20096)))))))

(deftest day-rates-key-test
  (testing "a relative schedule's rates depend on the account's day"
    (is (= ["prd.1" "ver.1" 20089] (SUT/day-rates-key account flat))))
  (testing "a fixed schedule's are the version's alone"
    (is (= ["prd.1" "ver.1" nil]
           (SUT/day-rates-key
            account
            (assoc flat
                   :schedule
                   {:fixed {:periods [{:from-on 20089 :rates-bps [100]}]}}))))))

(deftest accrue-test
  (testing "a version paying no interest accrues nothing"
    (is (nil? (SUT/accrue account earning-balances 27397 nil))))
  (testing "a rate with nowhere to put it accrues nothing either"
    ;; Logged rather than rejected — nothing the account did caused it.
    (is (nil?
         (SUT/accrue account spendable-only 27397 (day-rates flat 20100)))))
  (testing "a rate and a balance yield the amount and the carry together"
    ;; 1500 available at 100 bps opening at 27_397: 1500 * 100 * 100 +
    ;; 27_397 * 365 = 24_999_905, and / 365 = 68_492.
    (let [{:keys [amount closing-carry carry-delta principal opening-carry]}
          (SUT/accrue account earning-balances 27397 (day-rates flat 20100))]
      (testing "what the account earned, and the remainder it leaves"
        (is (= 0 amount))
        (is (= 68492 closing-carry))
        (is (= (- 68492 27397) carry-delta)))
      (testing "and what those were computed from, so the row explains itself"
        (testing "interest is earned on available, not posted"
          (is (= 1500 principal)))
        (is (= 27397 opening-carry))))))

(def ^:private current
  {:account-id "acc.1"
   :currency "GBP"
   :product-type :account-product-type-sub-ledger-current})

(def ^:private expense {"GBP" "led.expense" "EUR" "led.expense-eur"})

(deftest chunk-transactions-test
  (testing "a chunk that earned nothing whole records nothing"
    (is (= []
           (SUT/chunk-transactions "org.1"
                                   expense
                                   [[current {:amount 0}]]
                                   20260501))))
  (testing "CR each account's interest accrued, DR interest expense"
    (let [saver (assoc current
                       :account-id "acc.2"
                       :product-type :account-product-type-sub-ledger-savings)
          [tx] (SUT/chunk-transactions "org.1"
                                       expense
                                       [[current {:amount 3}]
                                        [saver {:amount 5}]
                                        [(assoc current :account-id "acc.3")
                                         {:amount 0}]]
                                       20260501)
          legs (:legs tx)]
      (is (= :transaction-type-interest-accrual (:transaction-type tx)))
      (is (= "accrue-org.1-20260501-GBP-acc.1" (:idempotency-key tx)))
      (is (= [["acc.1" :account-product-type-sub-ledger-current
               :balance-type-interest-accrued :leg-side-credit 3]
              ["acc.2" :account-product-type-sub-ledger-savings
               :balance-type-interest-accrued :leg-side-credit 5]
              ["led.expense" :account-product-type-general-ledger
               :balance-type-default :leg-side-debit 8]]
             (mapv (juxt :account-id :product-type :balance-type :side :amount)
                   legs)))))
  (testing "a negative accrual is a debit"
    (let [[tx] (SUT/chunk-transactions "org.1"
                                       expense
                                       [[current {:amount -2}]]
                                       20260501)]
      (is (= [["acc.1" :leg-side-debit 2] ["led.expense" :leg-side-credit 2]]
             (mapv (juxt :account-id :side :amount) (:legs tx))))))
  (testing "one transaction per currency"
    (let [txs (SUT/chunk-transactions
               "org.1"
               expense
               [[current {:amount 3}]
                [(assoc current :account-id "acc.2" :currency "EUR")
                 {:amount 4}]]
               20260501)]
      (is (= #{"GBP" "EUR"} (set (map :currency txs))))
      (is (= #{"led.expense" "led.expense-eur"}
             (set (map (comp :account-id last :legs) txs)))))))
