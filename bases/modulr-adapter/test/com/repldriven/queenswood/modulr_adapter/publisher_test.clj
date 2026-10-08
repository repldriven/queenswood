(ns com.repldriven.queenswood.modulr-adapter.publisher-test
  (:require
    [com.repldriven.queenswood.modulr-adapter.publisher :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(deftest amounts-convert-exactly-test
  (testing "major units in a string or a number"
    (is (= 1234 (SUT/amount->minor-units "12.34" "P1")))
    (is (= 2500 (SUT/amount->minor-units "25" "P1")))
    (is (= 1 (SUT/amount->minor-units 0.01 "P1"))))
  (testing "what cannot be converted without guessing is refused"
    (doseq [amount ["1.001" "-1.00" "abc" nil]]
      (is (= :payment/invalid-scheme-amount
             (error/kind (SUT/amount->minor-units amount "P1")))
          (str amount)))))

(deftest timestamps-test
  (is (= 1790613486000 (SUT/epoch-millis "2026-09-28T16:38:06+0000")))
  (is (= 1790613486841 (SUT/epoch-millis "2026-09-28T16:38:06.841+0000")))
  (testing "a malformed one reads as now"
    (is (pos? (SUT/epoch-millis "2024-02-02T02:02:022+0000")))))

(deftest compliance-test
  (let [inbound {:id "P1"
                 :type "PAYIN"
                 :details {:amount 3
                           :currency "GBP"
                           :payee {:sortCode "040010"
                                   :accountNumber "00001457"}}}
        status (fn [s]
                 {:ComplianceStatus s :EventTime "2026-09-28T16:38:06+0000"})]
    (testing "an inbound held is held for its payee"
      (let [[{:keys [event-name dedup-key data]}]
            (SUT/compliance (status "HELD") inbound)]
        (is (= "transaction-held" event-name))
        (is (= "P1:held" dedup-key))
        (is (= {:end-to-end-id "P1"
                :debit-credit-code :debit-credit-code-credit
                :amount 300
                :creditor-bban "04001000001457"}
               (select-keys data
                            [:end-to-end-id :debit-credit-code :amount
                             :creditor-bban])))))
    (testing "an inbound returned goes back to its sender"
      (let [[{:keys [data]}] (SUT/compliance (status "RETURNED") inbound)]
        (is (= :debit-credit-code-credit (:debit-credit-code data)))
        (is (:is-return data))))
    (testing "a release reports nothing"
      (is (= [] (SUT/compliance (status "RELEASED") inbound))))))

(deftest returned-test
  (let [payin {:PaymentId "P9"
               :Type "PO_REV"
               :Amount "12.50"
               :Currency "GBP"
               :ReturnReason "BENACCCLOSED"
               :OriginalSchemeId "MODULO00P1"
               :PaymentAppliedTime "2026-09-28T16:38:06+0000"
               :Payee {:Identifier {:SortCode "040010"
                                    :AccountNumber "00001457"}}}
        original {:id "P1" :type "PAYOUT" :externalReference "pmt-01K6A3Z9X0"}]
    (testing
      "a payment the adapter sent is returned, keyed on its end-to-end id"
      (let [[{:keys [event-name dedup-key data]} :as all]
            (SUT/returned payin
                          original
                          {:kind :modulr-outbound-intent-kind-payment})]
        (is (= 1 (count all)))
        (is (= "transaction-returned" event-name))
        (is (= "pmt.01K6A3Z9X0:returned" dedup-key))
        (is (= {:end-to-end-id "pmt.01K6A3Z9X0"
                :debit-credit-code :debit-credit-code-debit
                :scheme-transaction-id "P9"
                :amount 1250
                :reason-code "AC04"
                :reason "BENACCCLOSED"
                :timestamp-returned 1790613486000}
               (select-keys data
                            [:end-to-end-id :debit-credit-code
                             :scheme-transaction-id :amount :reason-code
                             :reason :timestamp-returned])))))
    (testing "a return matching no payment is money arriving where it landed"
      (doseq [[original intent] [[nil nil]
                                 [original
                                  {:kind :modulr-outbound-intent-kind-transfer}]
                                 [original nil]]]
        (let [[{:keys [event-name data]}] (SUT/returned payin original intent)]
          (is (= "transaction-settled" event-name))
          (is (= :debit-credit-code-credit (:debit-credit-code data)))
          (is (= "04001000001457" (:creditor-bban data))))))))
