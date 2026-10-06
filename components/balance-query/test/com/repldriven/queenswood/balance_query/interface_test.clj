(ns com.repldriven.queenswood.balance-query.interface-test
  (:require
    [com.repldriven.queenswood.balance-query.interface :as SUT]

    [clojure.test :refer [deftest is testing]]))

(defn- balance
  [product-type balance-type balance-status credit debit]
  {:product-type product-type
   :balance-type balance-type
   :balance-status balance-status
   :credit credit
   :debit debit})

(def ^:private cust :product-type-sub-ledger-current)

(def ^:private customer-balances
  [(balance cust :balance-type-default :balance-status-posted 10000 2000)
   (balance cust :balance-type-default :balance-status-pending-incoming 500 0)
   (balance cust :balance-type-default :balance-status-pending-outgoing 0 300)])

(def ^:private gl-balances
  "Bank-side GL account — a single default/posted bucket, no pendings,
  tagged general-ledger."
  [(balance :product-type-general-ledger
            :balance-type-default :balance-status-posted
            50000 10000)])

(def ^:private savings-balances
  "A customer account that has accrued interest: the three default
  buckets plus interest-accrued/posted, which must not count toward
  posted or available (it sits at posted status but isn't spendable)."
  (conj
   customer-balances
   (balance cust :balance-type-interest-accrued :balance-status-posted 750 0)))

(deftest posted-balance-test
  (testing "nets the default/posted bucket"
    (is (= {:value 8000 :currency "GBP"}
           (SUT/posted-balance customer-balances "GBP"))))
  (testing "excludes interest-accrued/posted"
    (is (= {:value 8000 :currency "GBP"}
           (SUT/posted-balance savings-balances "GBP"))))
  (testing "a GL/ledger account has a posted book balance"
    (is (= {:value 40000 :currency "GBP"}
           (SUT/posted-balance gl-balances "GBP"))))
  (testing "zero when no posted bucket"
    (is (= {:value 0 :currency "GBP"} (SUT/posted-balance [] "GBP")))))

(deftest available-balance-test
  (testing
    "settled default less committed outgoings, excluding unsettled incoming"
    ;; 8000 posted - 300 pending-outgoing; the 500 pending-incoming is
    ;; NOT credited (worst case) = 7700
    (is (= {:value 7700 :currency "GBP"}
           (SUT/available-balance customer-balances "GBP"))))
  (testing "excludes interest-accrued/posted"
    (is (= {:value 7700 :currency "GBP"}
           (SUT/available-balance savings-balances "GBP"))))
  (testing "a GL/ledger account has no available (posted book balance only)"
    (is (= {:value 0 :currency "GBP"}
           (SUT/available-balance gl-balances "GBP"))))
  (testing "empty balances returns zero"
    (is (= {:value 0 :currency "GBP"} (SUT/available-balance [] "GBP")))))

(deftest trial-balance-test
  (testing "a balanced currency: Σdebit equals Σcredit, accounts counted"
    ;; asset £1000 (debit-normal, posted −100000) balances liability £900
    ;; + equity £100 (credit-normal).
    (is (= [{:currency "GBP" :debit 100000 :credit 100000 :accounts 3}]
           (SUT/trial-balance
            [{:currency "GBP" :normal-side :debit :value -100000}
             {:currency "GBP" :normal-side :credit :value 90000}
             {:currency "GBP" :normal-side :credit :value 10000}]))))
  (testing "currencies are grouped, never summed together"
    (is (= #{{:currency "GBP" :debit 100000 :credit 100000 :accounts 2}
             {:currency "USD" :debit 5000 :credit 5000 :accounts 2}}
           (set (SUT/trial-balance
                 [{:currency "GBP" :normal-side :debit :value -100000}
                  {:currency "GBP" :normal-side :credit :value 100000}
                  {:currency "USD" :normal-side :debit :value -5000}
                  {:currency "USD" :normal-side :credit :value 5000}])))))
  (testing "an unbalanced currency surfaces the gap (debit not equal credit)"
    (is (= [{:currency "GBP" :debit 100000 :credit 90000 :accounts 2}]
           (SUT/trial-balance
            [{:currency "GBP" :normal-side :debit :value -100000}
             {:currency "GBP" :normal-side :credit :value 90000}]))))
  (testing "no entries yields no blocks" (is (= [] (SUT/trial-balance [])))))

(deftest available-delta-test
  (let [leg (fn [status side amount]
              {:balance-type :balance-type-default
               :balance-status status
               :side side
               :amount amount})]
    (testing "a debit to posted lowers available, a credit raises it"
      (is (= -300
             (SUT/available-delta
              [(leg :balance-status-posted :leg-side-debit 300)])))
      (is (= 300
             (SUT/available-delta
              [(leg :balance-status-posted :leg-side-credit 300)]))))
    (testing "a reservation into pending-outgoing lowers it too"
      (is (= -50
             (SUT/available-delta
              [(leg :balance-status-pending-outgoing :leg-side-debit 50)]))))
    (testing "settling a reservation moves it between buckets, not overall"
      (is (= 0
             (SUT/available-delta
              [(leg :balance-status-pending-outgoing :leg-side-credit 50)
               (leg :balance-status-posted :leg-side-debit 50)]))))
    (testing "pending-incoming and other balance types leave it alone"
      (is (= 0
             (SUT/available-delta
              [(leg :balance-status-pending-incoming :leg-side-credit 70)
               (assoc (leg :balance-status-posted :leg-side-credit 70)
                      :balance-type
                      :balance-type-interest-accrued)]))))))
