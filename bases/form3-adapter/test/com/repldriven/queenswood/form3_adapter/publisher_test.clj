(ns com.repldriven.queenswood.form3-adapter.publisher-test
  (:require
    [com.repldriven.queenswood.form3-adapter.publisher :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private inbound
  {:id "P1"
   :attributes {:amount "12.50"
                :currency "GBP"
                :end_to_end_reference "E2E-1"
                :reference "Lunch"
                :beneficiary_party {:bank_id "040075"
                                    :account_number "30000001"}
                :debtor_party {:account_name "Ford Prefect"}}})

(deftest amounts-convert-exactly-test
  (is (= 1250 (SUT/amount->minor-units "12.50" "P1")))
  (is (= 29 (SUT/amount->minor-units "0.29" "P1")))
  (doseq [amount ["1.001" "-1.00" "abc" nil 12.5]]
    (is (= :payment/invalid-scheme-amount
           (error/kind (SUT/amount->minor-units amount "P1")))
        (str amount))))

(deftest admission-request-test
  (is (= {:end-to-end-id "E2E-1"
          :scheme "fps"
          :creditor-bban "04007530000001"
          :amount 1250
          :currency "GBP"
          :debtor-name "Ford Prefect"
          :reference "Lunch"}
         (SUT/admission-request inbound))))

(deftest admitted-test
  (testing "a confirmed admission is money arriving, keyed on Form3's id"
    (let [[{:keys [event-name dedup-key data]}]
          (SUT/admitted inbound {:attributes {:status "confirmed"}} 1)]
      (is (= "transaction-settled" event-name))
      (is (= "P1:settled" dedup-key))
      (is (= {:scheme-transaction-id "P1"
              :end-to-end-id "E2E-1"
              :debit-credit-code :debit-credit-code-credit
              :amount 1250
              :creditor-bban "04007530000001"}
             (select-keys data
                          [:scheme-transaction-id :end-to-end-id
                           :debit-credit-code :amount :creditor-bban])))))
  (testing "a failed one is nothing"
    (is (= [] (SUT/admitted inbound {:attributes {:status "failed"}} 1)))))

(deftest submission-test
  (let [outbound (assoc-in inbound [:attributes :end_to_end_reference] "pmt.1")
        at-status (fn [status reason]
                    (first (SUT/submission outbound
                                           {:attributes {:status status
                                                         :status_reason reason}}
                                           1)))]
    (is (= "transaction-settled"
           (:event-name (at-status "delivery_confirmed" nil))))
    (is (= "AC04"
           (get-in (at-status "delivery_failed" "account_closed")
                   [:data :reason-code])))
    (is (nil? (at-status "queued_for_delivery" nil)))))

(deftest returned-test
  (let [outbound (assoc-in inbound [:attributes :end_to_end_reference] "pmt.1")
        [{:keys [event-name dedup-key data]}]
        (SUT/returned outbound
                      {:id "R1"
                       :attributes
                       {:amount "12.50" :currency "GBP" :return_code "AC04"}}
                      1)]
    (is (= "transaction-returned" event-name))
    (is (= "pmt.1:returned" dedup-key))
    (is (= {:reason-code "AC04" :scheme-transaction-id "R1" :amount 1250}
           (select-keys data [:reason-code :scheme-transaction-id :amount])))
    (testing "a code that is not ISO 20022 reads as NARR"
      (is (= "NARR"
             (get-in (first (SUT/returned outbound
                                          {:id "R2"
                                           :attributes {:amount "1.00"
                                                        :currency "GBP"
                                                        :return_code
                                                        "00000002"}}
                                          1))
                     [:data :reason-code]))))))
