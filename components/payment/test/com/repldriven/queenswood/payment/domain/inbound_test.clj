(ns com.repldriven.queenswood.payment.domain.inbound-test
  (:require
    [com.repldriven.queenswood.payment.domain.checks :as checks]
    [com.repldriven.queenswood.payment.domain.fixtures :as fixtures]
    [com.repldriven.queenswood.payment.domain.inbound :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(deftest inbound-payment->transaction-test
  (let [tx (SUT/inbound-payment->transaction {:scheme-transaction-id "stx-1"
                                              :currency "GBP"
                                              :amount 1000
                                              :reference "Invoice"}
                                             (fixtures/account "creditor" "GBP")
                                             "internal"
                                             (fixtures/allow-all)
                                             (fixtures/empty-aggregates
                                              :inbound-payment))]
    (testing "scheme-transaction-id becomes the idempotency-key"
      (is (= "stx-1" (:idempotency-key tx)))
      (is (= :transaction-type-inbound-transfer (:transaction-type tx))))
    (testing
      "GL 1100 cash-at-correspondent debited, customer (default) credited"
      ;; Post-CoA: when the creditor is identified via BBAN match, inbound
      ;; settles direct to the bank's 1100 Cash at correspondent on
      ;; `:balance-type-default :balance-status-posted`.
      (let [debit (fixtures/side tx :leg-side-debit)
            credit (fixtures/side tx :leg-side-credit)]
        (is (= "internal" (:account-id debit)))
        (is (= :balance-type-default (:balance-type debit)))
        (is (= :balance-status-posted (:balance-status debit)))
        (is (= 1000 (:amount debit)))
        (is (= "creditor" (:account-id credit)))
        (is (= :balance-type-default (:balance-type credit)))
        (is (= :balance-status-posted (:balance-status credit)))
        (is (= 1000 (:amount credit)))))))

(deftest account-refusal-test
  (is (= "AC01" (:reason-code (SUT/account-refusal nil))))
  (is (= "AC04"
         (:reason-code
          (SUT/account-refusal
           (fixtures/account "a" "GBP" :cash-account-status-closed)))))
  (is (= "AC04"
         (:reason-code
          (SUT/account-refusal
           (fixtures/account "a" "GBP" :cash-account-status-closing)))))
  (is (= "AC06"
         (:reason-code
          (SUT/account-refusal
           (fixtures/account "a" "GBP" :cash-account-status-suspended)))))
  (is (= "AC06"
         (:reason-code
          (SUT/account-refusal
           (fixtures/account "a" "GBP" :cash-account-status-opening)))))
  (is (nil? (SUT/account-refusal (fixtures/account "a" "GBP")))))

(deftest acceptance-refusal-test
  (testing "another currency is AM03"
    (is (= "AM03"
           (:reason-code (SUT/acceptance-refusal (SUT/check-inbound-acceptance
                                                  {:currency "EUR"}
                                                  (fixtures/account "a" "GBP")
                                                  (fixtures/allow-all)
                                                  (fixtures/empty-aggregates
                                                   :inbound-payment)))))))
  (testing "a policy refusal is AG01"
    (is (= "AG01"
           (:reason-code (SUT/acceptance-refusal (SUT/check-inbound-acceptance
                                                  {:currency "GBP"}
                                                  (fixtures/account "a" "GBP")
                                                  []
                                                  (fixtures/empty-aggregates
                                                   :inbound-payment))))))))

(deftest admitted-inbound-test
  (let [admitted (SUT/admitted-inbound-payment {:end-to-end-id "e2e-1"
                                                :scheme "fps"
                                                :currency "GBP"
                                                :amount 250
                                                :reference "Lunch"}
                                               "acc.1"
                                               "bnk.1"
                                               20260928)
        tx (SUT/admitted-inbound->transaction admitted
                                              (fixtures/account "acc.1" "GBP")
                                              "1100")]
    (testing "an admission is recorded admitted, with nothing posted"
      (is (= :inbound-payment-status-admitted (:status admitted)))
      (is (not (contains? admitted :transaction-id))))
    (testing "its settlement credits the account from 1100"
      (is (= "acc.1" (:account-id (fixtures/side tx :leg-side-credit))))
      (is (= "1100" (:account-id (fixtures/side tx :leg-side-debit))))
      (is (= "Lunch" (:reference tx)))
      (is (fixtures/balanced? tx)))))

(defn- inbound-count-capped
  [cap]
  [{:status :policy-status-active
    :capabilities [{:effect :effect-allow
                    :kind {:inbound-payment
                           {:action :inbound-payment-action-receive}}}]
    :limits [{:kind {:inbound-payment {}}
              :bound {:kind {:max {:aggregate
                                   {:kind {:count {:value cap
                                                   :window
                                                   :time-window-daily}}}}}}}]}])

(defn- inbound-count
  [n]
  {:inbound-payment {#{:bank-id :business-day} n}})

(deftest check-inbound-acceptance-test
  (let [data {:currency "GBP" :amount 100}
        creditor (fixtures/account "creditor" "GBP")]
    (testing "an acceptable inbound passes"
      (is (nil? (SUT/check-inbound-acceptance data
                                              creditor
                                              (fixtures/allow-all)
                                              (inbound-count 0)))))
    (testing "a currency the creditor account does not hold is refused"
      (let [result (SUT/check-inbound-acceptance {:currency "EUR" :amount 100}
                                                 creditor
                                                 (fixtures/allow-all)
                                                 (inbound-count 0))]
        (is (checks/refused? result))
        (is (= :payment/currency-mismatch (error/kind result)))))
    (testing "no receive capability is refused"
      (let [result
            (SUT/check-inbound-acceptance data creditor [] (inbound-count 0))]
        (is (checks/refused? result))
        (is (= :policy/denied (error/kind result)))))
    (testing "an inbound past the daily count is refused"
      (let [result (SUT/check-inbound-acceptance data
                                                 creditor
                                                 (inbound-count-capped 2)
                                                 (inbound-count 2))]
        (is (checks/refused? result))
        (is (= :policy/limit-exceeded (error/kind result)))))
    (testing "a failure is not a refusal"
      (is (not (checks/refused? (error/fail :payment/settle-inbound
                                            {:message "Failed"})))))))

(deftest inbound-release->transaction-test
  (let [held {:payment-id "pmt-held"
              :bank-id "bank"
              :currency "GBP"
              :amount 700
              :business-day 20000}
        creditor (fixtures/account "creditor" "GBP")]
    (testing "an accepted release credits the creditor from 1100"
      (let [tx (SUT/inbound-release->transaction held
                                                 creditor
                                                 "1100"
                                                 (fixtures/allow-all)
                                                 (inbound-count 0))]
        (is (= "release-in-pmt-held" (:idempotency-key tx)))
        (is (= {:account-id "1100" :amount 700}
               (select-keys (fixtures/side tx :leg-side-debit)
                            [:account-id :amount])))
        (is (= {:account-id "creditor" :amount 700}
               (select-keys (fixtures/side tx :leg-side-credit)
                            [:account-id :amount])))
        (is (fixtures/balanced? tx))))
    (testing "a release past the daily count is refused"
      (let [result (SUT/inbound-release->transaction held
                                                     creditor
                                                     "1100"
                                                     (inbound-count-capped 2)
                                                     (inbound-count 2))]
        (is (checks/refused? result))
        (is (= :policy/limit-exceeded (error/kind result)))))
    (testing "a release without the receive capability is refused"
      (let [result (SUT/inbound-release->transaction held
                                                     creditor
                                                     "1100"
                                                     []
                                                     (inbound-count 0))]
        (is (checks/refused? result))
        (is (= :policy/denied (error/kind result)))))))

(deftest release-count-test
  (let [held {:business-day 20000}]
    (testing "a release on the held day leaves the held record uncounted"
      (is (= 2 (SUT/release-count 3 held 20000))))
    (testing "a release on a later day counts every inbound of that day"
      (is (= 3 (SUT/release-count 3 held 20001))))))

(deftest suspended-from-held-test
  (let [held {:payment-id "pmt-held"
              :creditor-account-id "creditor"
              :scheme-transaction-id "held-placeholder"
              :status :inbound-payment-status-held
              :updated-at 1700000000000}
        suspended (SUT/suspended-from-held held
                                           "stx-9"
                                           "txn-9"
                                           {:reason-code "AC04"
                                            :reason "The account is closed"})]
    (is (= :inbound-payment-status-suspended (:status suspended)))
    (is (= "stx-9" (:scheme-transaction-id suspended)))
    (is (= "txn-9" (:transaction-id suspended)))
    (is (= "creditor" (:creditor-account-id suspended)))
    (is (= "AC04" (:suspended-reason-code suspended)))
    (is (= "The account is closed" (:suspended-reason suspended)))
    (is (>= (:updated-at suspended) (:updated-at held)))))

(deftest suspended-inbound-payment-test
  (let [suspended (SUT/suspended-inbound-payment {:scheme-transaction-id "stx-3"
                                                  :end-to-end-id "e2e-3"
                                                  :scheme "fps"
                                                  :currency "GBP"
                                                  :amount 500}
                                                 "bank" 20000
                                                 "txn-3" {:reason-code "AG01"
                                                          :reason "Refused"})]
    (testing "carries the reason it was parked for"
      (is (= :inbound-payment-status-suspended (:status suspended)))
      (is (= "AG01" (:suspended-reason-code suspended)))
      (is (= "Refused" (:suspended-reason suspended))))))

(def ^:private suspended
  {:payment-id "pmt-s"
   :bank-id "bank"
   :scheme-transaction-id "stx-s"
   :end-to-end-id "e2e-s"
   :creditor-account-id "creditor"
   :currency "GBP"
   :amount 700
   :reference "Rent"
   :status :inbound-payment-status-suspended
   :suspended-reason-code "AC04"
   :suspended-reason "The account is closed"})

(deftest returns-inbound?-test
  (testing "an inbound is returned where the provider returns"
    (is (SUT/returns-inbound? {:returns ["inbound"]})))
  (testing "and parked where it does not"
    (is (not (SUT/returns-inbound? {:returns []})))
    (is (not (SUT/returns-inbound? nil)))))

(deftest return-payment-test
  (is (= {:payment-id "pmt-s"
          :end-to-end-id "e2e-s"
          :scheme-transaction-id "stx-s"
          :amount 700
          :currency "GBP"
          :reason-code "AC04"
          :reason "The account is closed"}
         (SUT/return-payment suspended))))

(deftest inbound-return->transaction-test
  (let [tx (SUT/inbound-return->transaction suspended "cash" "suspense")]
    (testing "is an inbound return"
      (is (= :transaction-type-inbound-return (:transaction-type tx)))
      (is (= "return-in-pmt-s" (:idempotency-key tx))))
    (testing "debits suspense and credits 1100 by the amount parked"
      (is (= {:account-id "suspense" :amount 700}
             (select-keys (fixtures/side tx :leg-side-debit)
                          [:account-id :amount])))
      (is (= {:account-id "cash" :amount 700}
             (select-keys (fixtures/side tx :leg-side-credit)
                          [:account-id :amount])))
      (is (fixtures/balanced? tx)))
    (testing "names the account it was paid into"
      (is (= "creditor" (:scheme-account-id tx))))))

(deftest select-hold-to-return-test
  (let [hold-a {:payment-id "pmt-a"}
        hold-b {:payment-id "pmt-b"}]
    (testing "no open hold selects nothing"
      (is (nil? (SUT/select-hold-to-return [] "e2e-1"))))
    (testing "one open hold is selected"
      (is (= hold-a (SUT/select-hold-to-return [hold-a] "e2e-1"))))
    (testing "two open holds fail, naming the id and both candidates"
      (let [result (SUT/select-hold-to-return [hold-a hold-b] "e2e-1")]
        (is (error/error? result))
        (is (= :payment/ambiguous-hold (error/kind result)))
        (is (= {:end-to-end-id "e2e-1" :payment-ids ["pmt-a" "pmt-b"]}
               (select-keys (error/payload result)
                            [:end-to-end-id :payment-ids])))
        (is (string? (:message (error/payload result))))))))
