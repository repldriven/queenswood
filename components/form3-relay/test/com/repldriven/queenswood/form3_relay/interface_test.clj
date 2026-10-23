(ns com.repldriven.queenswood.form3-relay.interface-test
  (:require
    [com.repldriven.queenswood.form3-relay.interface :as SUT]

    [clojure.test :refer [deftest is testing]]))

(deftest reason-codes-test
  (testing "Faster Payments reject codes as ISO 20022 status reasons"
    (is (= "AC03" (SUT/reason-code "1114")))
    (is (= "AC07" (SUT/reason-code "1160")))
    (is (= "MD07" (SUT/reason-code "1167")))
    (is (= "AM04" (SUT/reason-code "1172")))
    (is (= "NARR" (SUT/reason-code "1163")))
    (is (= "NARR" (SUT/reason-code nil))))
  (testing "Faster Payments return codes as ISO 20022 return reasons"
    (is (= "AC01" (SUT/return-reason-code "00000001")))
    (is (= "AC04" (SUT/return-reason-code "00000002")))
    (is (= "FOCR" (SUT/return-reason-code "00000007")))
    (is (= "NARR" (SUT/return-reason-code "00000005")))
    (is (= "NARR" (SUT/return-reason-code "AC04"))))
  (testing "the platform's refusals as the return codes Form3 takes"
    (is (= "00000001" (SUT/return-code "AC01")))
    (is (= "00000002" (SUT/return-code "AC04")))
    (is (= "00000008" (SUT/return-code "AM03")))
    (is (= "00000014" (SUT/return-code "RR04"))))
  (testing "and as Form3's admission reasons"
    (is (= "unknown_accountnumber" (SUT/admission-reason "AC01")))
    (is (= "account_closed" (SUT/admission-reason "AC04")))
    (is (= "account_closed_stopped" (SUT/admission-reason "AC06")))
    (is (= "account_closed_currency" (SUT/admission-reason "AM03")))
    (is (= "account_closed_terms_and_conditions" (SUT/admission-reason "AG01")))
    (is (= "transaction_forbidden" (SUT/admission-reason "RR04")))))

(deftest outcomes-test
  (let [base {:provider-payment-id "P1"
              :end-to-end-id "pmt.1"
              :amount 150
              :currency "GBP"
              :at 1}]
    (is (= "provider-payment-settled"
           (:event-name (SUT/payment-outcome
                         (assoc base :status "delivery_confirmed")))))
    (is (= "P1:rejected"
           (:dedup-key (SUT/payment-outcome
                        (assoc base :status "limit_check_failed")))))
    (is (nil? (SUT/payment-outcome (assoc base :status "accepted"))))
    (testing "a failure keeps Form3's code and its description as the reason"
      (is (= {:reason-code "AC07"
              :cancellation-reason "1160: Beneficiary account closed"}
             (select-keys (:data (SUT/payment-outcome
                                  (assoc base
                                         :status "delivery_failed"
                                         :status-reason "account_closed"
                                         :scheme-status-code "1160"
                                         :scheme-status-description
                                         "Beneficiary account closed")))
                          [:reason-code :cancellation-reason]))))
    (testing "a failure with no scheme code is NARR, its status the reason"
      (is (= {:reason-code "NARR" :cancellation-reason "limit_check_failed"}
             (select-keys (:data (SUT/payment-outcome
                                  (assoc base :status "limit_check_failed")))
                          [:reason-code :cancellation-reason]))))))

(deftest major-units-test
  (is (= "1.50" (SUT/->major-units 150)))
  (is (= "0.07" (SUT/->major-units 7))))
