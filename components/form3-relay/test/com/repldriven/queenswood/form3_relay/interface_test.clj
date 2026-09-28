(ns com.repldriven.queenswood.form3-relay.interface-test
  (:require
    [com.repldriven.queenswood.form3-relay.interface :as SUT]

    [clojure.test :refer [deftest is testing]]))

(deftest reason-codes-test
  (testing "Form3's reasons as ISO 20022 codes"
    (is (= "AC01" (SUT/reason-code "unknown_accountnumber")))
    (is (= "AC04" (SUT/reason-code "account_closed_beneficiary_deceased")))
    (is (= "AC06" (SUT/reason-code "blocked_account")))
    (is (= "NARR" (SUT/reason-code "invalid_beneficiary_details")))
    (is (= "NARR" (SUT/reason-code nil))))
  (testing "and the platform's refusals as Form3's admission reasons"
    (is (= "unknown_accountnumber" (SUT/admission-reason "AC01")))
    (is (= "account_closed" (SUT/admission-reason "AC04")))
    (is (= "blocked_account" (SUT/admission-reason "AC06")))
    (is (= "transaction_forbidden" (SUT/admission-reason "AM03")))))

(deftest outcomes-test
  (let [base {:provider-payment-id "P1"
              :end-to-end-id "pmt.1"
              :amount 150
              :currency "GBP"
              :at 1}]
    (is (= "transaction-settled"
           (:event-name (SUT/payment-outcome
                         (assoc base :status "delivery_confirmed")))))
    (is (= "P1:rejected"
           (:dedup-key (SUT/payment-outcome
                        (assoc base :status "limit_check_failed")))))
    (is (nil? (SUT/payment-outcome (assoc base :status "accepted"))))))

(deftest major-units-test
  (is (= "1.50" (SUT/->major-units 150)))
  (is (= "0.07" (SUT/->major-units 7))))
