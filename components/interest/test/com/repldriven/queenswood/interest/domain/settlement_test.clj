(ns com.repldriven.queenswood.interest.domain.settlement-test
  "What a closure does with an account's accrued interest under each
  interest term a version's closure terms may hold, and the double entry
  recording it."
  (:require
    [com.repldriven.queenswood.interest.domain.settlement :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private savings :account-product-type-sub-ledger-savings)

(defn- bucket
  [balance-type credit]
  {:product-type savings
   :balance-type balance-type
   :balance-status :balance-status-posted
   :currency "GBP"
   :credit credit
   :debit 0})

(def ^:private accrued-balances
  [(bucket :balance-type-default 100000)
   (bucket :balance-type-interest-accrued 250)])

(def ^:private account
  {:account-id "acc.1"
   :product-type savings
   :currency "GBP"
   :version-from-on 20454})

(def ^:private version
  "A version paying 36.5% on the whole balance, 100 a day on £1,000."
  {:interest-terms
   {:accrual {:banding :interest-banding-marginal
              :day-count :interest-day-count-actual-365
              :bands [0]
              :schedule {:relative {:unit :interest-period-unit-month
                                    :periods [{:after 0 :rates-bps [3650]}]}}}
    :payment {:kind {:closure-day {}}}}})

(defn- settle
  ([kind] (settle kind accrued-balances))
  ([kind balances]
   (SUT/settle "bnk.1"
               account
               version
               {:kind kind}
               balances
               "gl.5100"
               20500)))

(defn- legs
  [transaction]
  (mapv (juxt :account-id :balance-type :side :amount) (:legs transaction)))

(deftest settle-test
  (testing "nothing accrued settles nothing"
    (is (nil? (settle {:pay {}} (take 1 accrued-balances))))
    (is (nil? (settle {:forfeit {}} (take 1 accrued-balances)))))
  (testing "pay moves all of it to the default bucket"
    (let [{:keys [paid forfeited payment forfeiture]} (settle {:pay {}})]
      (is (= [250 0] [paid forfeited]))
      (is (nil? forfeiture))
      (is (= :transaction-type-interest-capitalization
             (:transaction-type payment)))
      (is (= "settle-acc.1-20500-pay" (:idempotency-key payment)))
      (is (= [["acc.1" :balance-type-interest-accrued :leg-side-debit 250]
              ["acc.1" :balance-type-default :leg-side-credit 250]]
             (legs payment)))))
  (testing "forfeit takes all of it back against interest expense"
    (let [{:keys [paid forfeited payment forfeiture]} (settle {:forfeit {}})]
      (is (= [0 250] [paid forfeited]))
      (is (nil? payment))
      (is (= :transaction-type-interest-forfeiture
             (:transaction-type forfeiture)))
      (is (= [["acc.1" :balance-type-interest-accrued :leg-side-debit 250]
              ["gl.5100" :balance-type-default :leg-side-credit 250]]
             (legs forfeiture)))
      (is (= :account-product-type-general-ledger
             (:product-type (second (:legs forfeiture)))))))
  (testing "forfeit-days takes that many days' interest and pays the rest"
    (let [two-days {:forfeit-days {:days 2}}
          {:keys [paid forfeited payment forfeiture]} (settle two-days)]
      (is (= [50 200] [paid forfeited]))
      (is (= 50 (:amount (first (:legs payment)))))
      (is (= 200 (:amount (first (:legs forfeiture)))))))
  (testing "but never more than has accrued"
    (let [{:keys [paid forfeited payment]} (settle {:forfeit-days {:days 90}})]
      (is (= [0 250] [paid forfeited]))
      (is (nil? payment)))))
