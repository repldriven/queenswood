(ns com.repldriven.queenswood.interest.domain.chart-test
  "What each kind of run needs out of a bank's chart of accounts, and
  what it says when the chart cannot supply it."
  (:require
    [com.repldriven.queenswood.interest.domain.chart :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(defn- ledger-account
  [gl-account-code currency id]
  {:gl-account-code gl-account-code :currency currency :ledger-account-id id})

(defn- chart-in
  "A bank's nine flat rows in one currency, each id suffixed with the
  currency so two currencies never share an account."
  [currency]
  (mapv (fn [[gl-account-code id]]
          (ledger-account gl-account-code currency (str id "." currency)))
        {:gl-account-code-interest-expense "led.expense"
         :gl-account-code-interest-payable "led.payable"
         :gl-account-code-customer-deposits-current "led.current"
         :gl-account-code-customer-deposits-savings "led.savings"
         :gl-account-code-customer-deposits-term "led.term"
         :gl-account-code-own-funds "led.own-funds"
         :gl-account-code-suspense "led.suspense"}))

(def ^:private full-chart (into (chart-in "GBP") (chart-in "USD")))

(defn- without
  [chart gl-account-code currency]
  (vec (remove (fn [a]
                 (and (= gl-account-code (:gl-account-code a))
                      (= currency (:currency a))))
               chart)))

(deftest accrual-accounts-test
  (testing "accrual takes the two fixed roles and ignores the rest"
    (is (= {:expense "led.expense.GBP" :payable "led.payable.GBP"}
           (SUT/accrual-accounts full-chart "org.1" "GBP"))))
  (testing "each currency resolves its own rows out of the same chart"
    (is (= {:expense "led.expense.USD" :payable "led.payable.USD"}
           (SUT/accrual-accounts full-chart "org.1" "USD"))))
  (testing "a role present in one currency does not answer for another"
    ;; The USD expense row is still there, only GBP's is gone. A filter
    ;; on the role alone would resolve the USD account and post the
    ;; bank's GBP accrual into it.
    (let [chart (without full-chart :gl-account-code-interest-expense "GBP")
          result (SUT/accrual-accounts chart "org.1" "GBP")]
      (is (error/rejection? result))
      (is (= :interest/missing-gl-account (error/kind result)))
      (is (= {:gl-account-code :gl-account-code-interest-expense
              :currency "GBP"}
             (select-keys (error/payload result) [:gl-account-code :currency])))
      (is (= {:expense "led.expense.USD" :payable "led.payable.USD"}
             (SUT/accrual-accounts chart "org.1" "USD"))))))

(deftest capitalization-accounts-test
  (testing "capitalisation takes payable plus a control per product type"
    ;; Every product type that rolls into a control, including own
    ;; funds — which pays no interest today but would land here the day
    ;; it does.
    (is (= {:payable "led.payable.GBP"
            :controls {:product-type-sub-ledger-current "led.current.GBP"
                       :product-type-sub-ledger-savings "led.savings.GBP"
                       :product-type-sub-ledger-term-deposit "led.term.GBP"
                       :product-type-sub-ledger-own-funds "led.own-funds.GBP"}}
           (SUT/capitalization-accounts full-chart "org.1" "GBP"))))
  (testing "the controls of the currency asked for, not of the first row"
    (is (= {:payable "led.payable.USD"
            :controls {:product-type-sub-ledger-current "led.current.USD"
                       :product-type-sub-ledger-savings "led.savings.USD"
                       :product-type-sub-ledger-term-deposit "led.term.USD"
                       :product-type-sub-ledger-own-funds "led.own-funds.USD"}}
           (SUT/capitalization-accounts full-chart "org.1" "USD"))))
  (testing "a missing deposit control is a rejection, not a nil credit leg"
    ;; Every earning product type must have somewhere for its
    ;; capitalised interest to land before any of it moves.
    (let [result
          (SUT/capitalization-accounts
           (without full-chart :gl-account-code-customer-deposits-savings "USD")
           "org.1"
           "USD")]
      (is (error/rejection? result))
      (is (= :interest/missing-gl-account (error/kind result)))
      (is (= {:gl-account-code :gl-account-code-customer-deposits-savings
              :currency "USD"}
             (select-keys (error/payload result)
                          [:gl-account-code :currency])))))
  (testing "a missing payable is caught the same way"
    (is (error/rejection?
         (SUT/capitalization-accounts
          (without full-chart :gl-account-code-interest-payable "GBP")
          "org.1"
          "GBP")))))

(deftest by-currency-test
  (testing "a two-currency bank resolves two sets of roles up front"
    (is (= {"GBP" {:expense "led.expense.GBP" :payable "led.payable.GBP"}
            "USD" {:expense "led.expense.USD" :payable "led.payable.USD"}}
           (SUT/by-currency full-chart "org.1" SUT/accrual-accounts))))
  (testing "one currency short of a role fails the whole run before it starts"
    (let [result (SUT/by-currency
                  (without full-chart :gl-account-code-interest-payable "USD")
                  "org.1"
                  SUT/accrual-accounts)]
      (is (error/rejection? result))
      (is (= "USD" (:currency (error/payload result)))))))

(deftest accounts-for-test
  (let [gl (SUT/by-currency full-chart "org.1" SUT/accrual-accounts)]
    (testing "an entry gets the roles of its own currency"
      (is (= {:expense "led.expense.USD" :payable "led.payable.USD"}
             (SUT/accounts-for gl "org.1" "USD"))))
    (testing "a currency the chart says nothing about rejects at close"
      (let [result (SUT/accounts-for gl "org.1" "EUR")]
        (is (error/rejection? result))
        (is (= :interest/missing-gl-account (error/kind result)))
        (is (= "EUR" (:currency (error/payload result))))))))
