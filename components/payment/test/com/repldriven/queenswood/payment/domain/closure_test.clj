(ns com.repldriven.queenswood.payment.domain.closure-test
  (:require
    [com.repldriven.queenswood.payment.domain.closure :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private closing
  {:bank-id "bnk.test"
   :account-id "acc.closing"
   :product-type :account-product-type-sub-ledger-savings
   :currency "GBP"})

(def ^:private nominated
  (assoc closing
         :account-id "acc.nominated"
         :product-type :account-product-type-sub-ledger-current))

(def ^:private operation
  {:bank-id "bnk.test"
   :account-id "acc.closing"
   :operation-id "cao.close"
   :nominated-account-id "acc.nominated"})

(deftest close-requested?-test
  (let [recorded {:kind :cash-account-operation-kind-close
                  :status-before nil
                  :status-after :cash-account-operation-status-pending}]
    (testing "a close operation just recorded is carried out"
      (is (SUT/close-requested? recorded)))
    (testing "its settling, or any other operation, is not"
      (is (not (SUT/close-requested?
                (assoc recorded
                       :status-before :cash-account-operation-status-pending
                       :status-after
                       :cash-account-operation-status-completed))))
      (is (not
           (SUT/close-requested?
            (assoc recorded :kind :cash-account-operation-kind-suspend)))))))

(deftest default-held-test
  (is (= 1000
         (SUT/default-held [{:balance-type :balance-type-default
                             :balance-status :balance-status-posted
                             :credit 1500
                             :debit 500}
                            {:balance-type :balance-type-default
                             :balance-status :balance-status-pending-outgoing
                             :credit 0
                             :debit 200}
                            {:balance-type :balance-type-interest-accrued
                             :balance-status :balance-status-posted
                             :credit 25
                             :debit 0}]))))

(def ^:private balance-fee
  {:kind {:balance {:rate-bps 100 :min 500 :max 2000}}})

(deftest closure-fee-test
  (testing "a balance fee is its rate of what is held, rounded down"
    (is (= 1234 (SUT/closure-fee balance-fee 123456))))
  (testing "raised to its minimum and capped at its maximum"
    (is (= 500 (SUT/closure-fee balance-fee 10000)))
    (is (= 2000 (SUT/closure-fee balance-fee 1000000))))
  (testing "never more than is held"
    (is (= 300 (SUT/closure-fee balance-fee 300)))
    (is (= 300 (SUT/closure-fee {:kind {:flat {:amount 1000}}} 300))))
  (testing "a flat fee is its amount"
    (is (= 1000 (SUT/closure-fee {:kind {:flat {:amount 1000}}} 5000))))
  (testing "no fee, or nothing held, charges nothing"
    (is (zero? (SUT/closure-fee nil 5000)))
    (is (zero? (SUT/closure-fee balance-fee 0)))
    (is (zero? (SUT/closure-fee balance-fee -100)))))

(deftest check-held-test
  (testing "anything held from nothing up closes"
    (is (nil? (SUT/check-held closing 0)))
    (is (nil? (SUT/check-held closing 1000))))
  (testing "an overdrawn account is refused"
    (let [result (SUT/check-held closing -1)]
      (is (error/rejection? result))
      (is (= :payment/overdrawn-on-close (error/kind result))))))

(deftest transactions-test
  (testing "the fee moves from the account to fee income, keyed by the close"
    (let [{:keys [idempotency-key transaction-type legs]}
          (SUT/fee-transaction closing "cao.close" "gl.4100" 500 20500)]
      (is (= "closure-fee-cao.close" idempotency-key))
      (is (= :transaction-type-fee transaction-type))
      (is (= [["acc.closing" :leg-side-debit 500]
              ["gl.4100" :leg-side-credit 500]]
             (mapv (juxt :account-id :side :amount) legs)))))
  (testing "the balance moves to the nominated account, keyed by the close"
    (let [{:keys [idempotency-key transaction-type legs]}
          (SUT/closure-transaction closing "cao.close" nominated 1000 20500)]
      (is (= "closure-cao.close" idempotency-key))
      (is (= :transaction-type-closure transaction-type))
      (is (= [["acc.closing" :account-product-type-sub-ledger-savings
               :leg-side-debit]
              ["acc.nominated" :account-product-type-sub-ledger-current
               :leg-side-credit]]
             (mapv (juxt :account-id :product-type :side) legs))))))

(deftest closures-test
  (testing "a paid closure records what it settled, charged and moved"
    (let [paid (SUT/paid-closure operation
                                 "GBP"
                                 25 500
                                 1000 {:transaction-id "txn.1"})]
      (is (= {:status :account-closure-status-paid
              :operation-id "cao.close"
              :nominated-account-id "acc.nominated"
              :interest-paid 25
              :fee-charged 500
              :amount 1000
              :transaction-id "txn.1"}
             (select-keys paid
                          [:status :operation-id :nominated-account-id
                           :interest-paid :fee-charged :amount
                           :transaction-id])))
      (is (re-find #"^acl\." (:closure-id paid)))
      (is (= SUT/platform (:created-by paid)))))
  (testing "one that moved nothing names no transaction"
    (is (not (contains? (SUT/paid-closure operation "GBP" 0 0 0 nil)
                        :transaction-id))))
  (testing "a refused closure carries the rejection's message"
    (let [refused (SUT/refused-closure operation
                                       "GBP"
                                       (error/reject :policy/limit-exceeded
                                                     {:message "Too much"}))]
      (is (= :account-closure-status-refused (:status refused)))
      (is (= "Too much" (:refused-reason refused))))))
