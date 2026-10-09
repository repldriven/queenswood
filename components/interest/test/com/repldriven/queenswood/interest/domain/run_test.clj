(ns com.repldriven.queenswood.interest.domain.run-test
  "Which accounts a pass is in scope for, the daily-count limit that
  gates a run, whether it may close, and the record it leaves behind."
  (:require
    [com.repldriven.queenswood.interest.domain.run :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(deftest eligible-cash-account?-test
  (testing "an open sub-ledger account is in scope"
    (is (true? (SUT/eligible-cash-account?
                {:product-type :account-product-type-sub-ledger-current
                 :status :cash-account-status-opened}))))
  (testing "every product type is, not a chosen few"
    ;; Whether an instrument pays is its product's rate. Own funds pays
    ;; nothing today, and the pass must still visit it so that the day
    ;; someone sets a rate it starts accruing without a code change.
    (is (true? (SUT/eligible-cash-account?
                {:product-type :account-product-type-sub-ledger-own-funds
                 :status :cash-account-status-opened}))))
  (testing "a suspended account still earns — the bank still holds the money"
    ;; Suspension stops the customer moving their balance, not the bank
    ;; owing them for holding it.
    (is (true? (SUT/eligible-cash-account?
                {:product-type :account-product-type-sub-ledger-current
                 :status :cash-account-status-suspended}))))
  (testing "a ledger account falls out — it has a status, not an account one"
    ;; The bank's own books are not cash accounts and never reach a
    ;; pass, but nothing about this reads their status field either.
    (is (not (SUT/eligible-cash-account? {:status
                                          :ledger-account-status-open}))))
  (testing "a balance not yet settled, or no longer held, earns nothing"
    (is (not (SUT/eligible-cash-account? {:status
                                          :cash-account-status-opening})))
    (is (not (SUT/eligible-cash-account? {:status
                                          :cash-account-status-closing})))
    (is (not (SUT/eligible-cash-account?
              {:product-type :account-product-type-sub-ledger-savings
               :status :cash-account-status-closed})))))

(def ^:private accrual-limit-policies
  "One platform policy: at most one accrual run per org per day."
  [{:status :policy-status-active
    :limits [{:kind {:interest {:filters [{:action :interest-action-accrue}]}}
              :bound {:kind {:max {:aggregate {:kind {:count
                                                      {:value 1
                                                       :window
                                                       :time-window-daily}}}}}}
              :reason "at most one accrual run per day"}]}])

(deftest check-daily-count-test
  (testing "first run of the day passes — post-state count 1 is within max 1"
    (is (true? (SUT/check-daily-count accrual-limit-policies
                                      :accrual
                                      {:accrual {#{:bank-id :business-day}
                                                 0}}))))
  (testing "second run of the day is rejected — post-state 2 exceeds max 1"
    (let [result (SUT/check-daily-count accrual-limit-policies
                                        :accrual
                                        {:accrual {#{:bank-id :business-day}
                                                   1}})]
      (is (error/rejection? result))
      (is (= :policy/limit-exceeded (error/kind result)))))
  (testing "a kind with no matching limit is unconstrained"
    (is (true? (SUT/check-daily-count accrual-limit-policies
                                      :capitalize
                                      {:capitalize {#{:bank-id :business-day}
                                                    5}})))))

(deftest closed-test
  (testing "a finished pass leaves one closed record, never an open one"
    (let [run (SUT/closed "org.1" 20260501 :interest-run-kind-accrue)]
      (is (= :interest-run-status-closed (:status run)))
      (is (= "org.1" (:bank-id run)))
      (is (= 20260501 (:business-day run)))
      (is (= :interest-run-kind-accrue (:kind run)))
      (is (number? (:created-at run)))
      (is (number? (:closed-at run))))))

(deftest check-complete-test
  (testing "a pass with every account posted closes"
    (let [tally {:done 3 :skipped 2 :failed 0 :seen #{}}]
      (is (= tally (SUT/check-complete "bnk.1" 20260501 tally)))))
  (testing "a pass with a failed account is incomplete, with its counts"
    (let [res (SUT/check-complete "bnk.1"
                                  20260501
                                  {:done 3 :skipped 2 :failed 4 :seen #{}})]
      (is (= :interest/run-incomplete (error/kind res)))
      (is (= {:accounts-processed 5 :accounts-failed 4}
             (select-keys (error/payload res)
                          [:accounts-processed :accounts-failed]))))))
