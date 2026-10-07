(ns com.repldriven.queenswood.payment.domain.provider-transfer-test
  (:require
    [com.repldriven.queenswood.payment.domain.provider-transfer :as SUT]

    [clojure.test :refer [deftest is testing]]))

(defn- posted-leg
  [account-id side amount & {:as extra}]
  (merge {:account-id account-id
          :balance-type :balance-type-default
          :balance-status :balance-status-posted
          :side side
          :amount amount}
         extra))

(def ^:private accounts
  {:cash-accounts {"acc.a" "acc.a"
                   "acc.b" "acc.b"
                   "acc.house" "acc.house"
                   "acc.no" "acc.house"}
   :cash-at-correspondent-id "gl.1100"
   :own-funds "acc.house"})

(defn- transfers
  ([legs] (transfers legs nil))
  ([legs scheme-account-id]
   (SUT/provider-transfers
    {:legs legs}
    (assoc accounts :scheme-account-id scheme-account-id))))

(deftest provider-transfers-test
  (testing "an internal payment moves between the two accounts"
    (is (= [{:debtor "acc.a" :creditor "acc.b" :amount 500}]
           (transfers [(posted-leg "acc.a" :leg-side-debit 500)
                       (posted-leg "acc.b" :leg-side-credit 500)]))))
  (testing "interest capitalised is paid from the bank's own funds"
    (is (= [{:debtor "acc.house" :creditor "acc.a" :amount 7}]
           (transfers [(posted-leg "acc.a"
                                   :leg-side-debit 7
                                   :balance-type :balance-type-interest-accrued)
                       (posted-leg "acc.a" :leg-side-credit 7)]))))
  (testing "a reward from the house account"
    (is (= [{:debtor "acc.house" :creditor "acc.b" :amount 300}]
           (transfers [(posted-leg "acc.house" :leg-side-debit 300)
                       (posted-leg "acc.b" :leg-side-credit 300)]))))
  (testing "the scheme's own settlement moves nothing"
    (is (= []
           (transfers [(posted-leg "gl.1100" :leg-side-debit 900)
                       (posted-leg "acc.a" :leg-side-credit 900)]
                      "acc.a"))))
  (testing "an inbound parked in suspense moves to the bank's own funds"
    (is (= [{:debtor "acc.a" :creditor "acc.house" :amount 900}]
           (transfers [(posted-leg "gl.1100" :leg-side-debit 900)
                       (posted-leg "gl.2500" :leg-side-credit 900)]
                      "acc.a"))))
  (testing "money from outside the scheme is credited from outside"
    (is (= [{:debtor nil :creditor "acc.house" :amount 5000}]
           (transfers [(posted-leg "gl.1100" :leg-side-debit 5000)
                       (posted-leg "acc.house" :leg-side-credit 5000)]))))
  (testing "money leaving to 1100 without the scheme stays with own funds"
    (is (= [{:debtor "acc.a" :creditor "acc.house" :amount 50}]
           (transfers [(posted-leg "acc.a" :leg-side-debit 50)
                       (posted-leg "gl.1100" :leg-side-credit 50)]))))
  (testing "an account the provider holds nothing for is held in own funds"
    (is (= [{:debtor "acc.a" :creditor "acc.house" :amount 20}]
           (transfers [(posted-leg "acc.a" :leg-side-debit 20)
                       (posted-leg "acc.no" :leg-side-credit 20)]))))
  (testing "pending legs move nothing"
    (is (= []
           (transfers
            [(posted-leg "acc.a"
                         :leg-side-debit 20
                         :balance-status :balance-status-pending-outgoing)
             (posted-leg "gl.1200"
                         :leg-side-credit 20
                         :balance-status :balance-status-pending-outgoing)])))))

(deftest mirrors-nothing-test
  (let [nothing? (fn [legs scheme-account-id]
                   (SUT/mirrors-nothing? {:legs legs
                                          :scheme-account-id scheme-account-id}
                                         "gl.1100"))]
    (testing "a posting of pending legs alone"
      (let [legs [(posted-leg "acc.a"
                              :leg-side-debit 20
                              :balance-status :balance-status-pending-outgoing)
                  (posted-leg "gl.1200"
                              :leg-side-credit 20
                              :balance-status
                              :balance-status-pending-outgoing)]]
        (is (nothing? legs nil))
        (is (= [] (transfers legs)))))
    (testing "the scheme's own settlement, whatever its account's party"
      (doseq [scheme ["acc.a" "acc.no"]
              legs [[(posted-leg scheme :leg-side-debit 900)
                     (posted-leg "gl.1100" :leg-side-credit 900)
                     (posted-leg "gl.1200"
                                 :leg-side-debit 900
                                 :balance-status
                                 :balance-status-pending-outgoing)]
                    [(posted-leg "gl.1100" :leg-side-debit 900)
                     (posted-leg scheme :leg-side-credit 900)]]]
        (is (nothing? legs scheme))
        (is (= [] (transfers legs (get-in accounts [:cash-accounts scheme]))))))
    (testing "a posting that moves money is not nothing"
      (doseq [[legs scheme]
              [[[(posted-leg "acc.a" :leg-side-debit 500)
                 (posted-leg "acc.b" :leg-side-credit 500)] nil]
               [[(posted-leg "gl.1100" :leg-side-debit 900)
                 (posted-leg "gl.2500" :leg-side-credit 900)] "acc.a"]
               [[(posted-leg "gl.1100" :leg-side-debit 5000)
                 (posted-leg "acc.house" :leg-side-credit 5000)] nil]
               [[(posted-leg "acc.a" :leg-side-debit 100)
                 (posted-leg "gl.1100" :leg-side-credit 60)
                 (posted-leg "gl.1200" :leg-side-credit 40)] "acc.a"]]]
        (is (not (nothing? legs scheme)))
        (is (seq (transfers legs scheme)))))))

(deftest mirror-party-test
  (testing "an account with a provider account holds its own money"
    (is (= "acc.a"
           (SUT/mirror-party {:account-id "acc.a" :provider-account-id "A1"}
                             "acc.house"))))
  (testing "as does one the provider is opening"
    (is (= "acc.a"
           (SUT/mirror-party {:account-id "acc.a"
                              :account-status :cash-account-status-opening}
                             "acc.house"))))
  (testing "one the provider holds nothing for is held in own funds"
    (is (= "acc.house"
           (SUT/mirror-party {:account-id "acc.a"
                              :account-status :cash-account-status-opened}
                             "acc.house")))))

(deftest transfer-outcome-test
  (let [pending {:transfer-id "ptr.1"
                 :status :provider-transfer-status-pending}]
    (is (= :provider-transfer-status-failed
           (:status (SUT/transfer-outcome pending
                                          :provider-transfer-status-failed
                                          "Insufficient funds"))))
    (is (nil? (SUT/transfer-outcome
               (assoc pending :status :provider-transfer-status-completed)
               :provider-transfer-status-failed
               nil)))))
