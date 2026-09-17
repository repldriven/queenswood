(ns com.repldriven.queenswood.clearbank-adapter.publisher-test
  (:require
    [com.repldriven.queenswood.clearbank-adapter.clearbank :as clearbank]
    [com.repldriven.queenswood.clearbank-adapter.publisher :as SUT]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.json.interface :as json]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(defn- settled-json
  [transaction-id amount-text]
  (str "{\"TransactionId\":\""
       transaction-id
       "\","
       "\"Status\":\"Settled\","
       "\"Scheme\":\"FasterPayments\","
       "\"EndToEndTransactionId\":\"NOTPROVIDED\","
       "\"Amount\":"
       amount-text
       ","
       "\"CurrencyCode\":\"GBP\","
       "\"DebitCreditCode\":\"Credit\","
       "\"TimestampSettled\":\"2026-04-01T12:00:00Z\","
       "\"IsReturn\":false,"
       "\"Account\":{\"BBAN\":\"04000412345678\"},"
       "\"CounterpartAccount\":{}}"))

(defn- settled
  [overrides]
  (merge {:TransactionId "txn-1"
          :Status "Settled"
          :Scheme "FasterPayments"
          :EndToEndTransactionId "NOTPROVIDED"
          :Amount 0.29
          :CurrencyCode "GBP"
          :DebitCreditCode "Credit"
          :TimestampSettled "2026-04-01T12:00:00Z"
          :IsReturn false
          :Account {:BBAN "04000412345678"}
          :CounterpartAccount {}}
         overrides))

(defn- descriptor-amount
  [descriptors]
  (get-in descriptors [0 :data :amount]))

(deftest amount-converts-exactly-test
  (testing "every amount from 0.00 to 999.99, decoded from JSON text"
    (let [results (mapv (fn [minor-units]
                          (let [text (format "%d.%02d"
                                             (quot minor-units 100)
                                             (rem minor-units 100))
                                payload (json/read-str (settled-json "txn-1"
                                                                     text)
                                                       :key-fn
                                                       keyword)]
                            {:minor-units minor-units
                             :decoded (:Amount payload)
                             :mapped (descriptor-amount
                                      (SUT/inbound-payment-settled payload))}))
                        (range 100000))]
      (is (every? (fn [{:keys [decoded]}] (instance? Double decoded)) results)
          "the decoder yields a Double for a two-place amount")
      (is (empty? (remove (fn [{:keys [minor-units mapped]}]
                            (= minor-units mapped))
                          results)))))
  (testing "named amounts"
    (doseq [[amount expected] [[0.29 29] [0.01 1] [999.99 99999]
                               [1234.5 123450] [12 1200]]]
      (is (= expected
             (descriptor-amount (SUT/inbound-payment-settled
                                 (settled {:Amount amount}))))
          (str amount))))
  (testing "a third decimal place, a negative and a missing amount refuse"
    (doseq [amount [0.295 -1 nil]]
      (let [res (SUT/inbound-payment-settled (settled {:Amount amount}))]
        (is (error/rejection? res) (str amount))
        (is (= :payment/invalid-scheme-amount (error/kind res)))
        (is (= "txn-1" (:transaction-id (error/payload res))))))))

(deftest dedup-keys-test
  (testing "an inbound settlement is keyed on TransactionId"
    (is (= "txn-1:settled"
           (get-in (SUT/inbound-payment-settled (settled {})) [0 :dedup-key]))))
  (testing "an outbound settlement is keyed on EndToEndTransactionId"
    (is (= "pmt.1:settled"
           (get-in (SUT/outbound-payment-settled
                    (settled {:EndToEndTransactionId "pmt.1"
                              :DebitCreditCode "Debit"}))
                   [0 :dedup-key]))))
  (testing "an inbound rejection is keyed on TransactionId, with its BBAN"
    (let [[{:keys [dedup-key data]}] (SUT/inbound-payment-rejected
                                      {:TransactionId "txn-2"
                                       :EndToEndTransactionId "NOTPROVIDED"
                                       :CancellationCode "AC04"
                                       :DebitCreditCode "Credit"
                                       :IsReturn true
                                       :Account {:BBAN "04000412345678"}})]
      (is (= "txn-2:rejected" dedup-key))
      (is (= :debit-credit-code-credit (:debit-credit-code data)))
      (is (= "04000412345678" (:creditor-bban data)))))
  (testing "an inbound rejection without a BBAN carries none"
    (is (not (contains? (get-in (SUT/inbound-payment-rejected
                                 {:TransactionId "txn-3"
                                  :EndToEndTransactionId "NOTPROVIDED"
                                  :CancellationCode "AC04"
                                  :Account {}})
                                [0 :data])
                        :creditor-bban))))
  (testing "an outbound rejection is keyed on EndToEndTransactionId"
    (let [[{:keys [dedup-key data]}] (SUT/outbound-payment-rejected
                                      {:TransactionId "txn-4"
                                       :EndToEndTransactionId "pmt.2"
                                       :CancellationCode "AM09"
                                       :DebitCreditCode "Debit"})]
      (is (= "pmt.2:rejected" dedup-key))
      (is (= :debit-credit-code-debit (:debit-credit-code data)))))
  (testing "an inbound hold is keyed on four fields"
    (let [[{:keys [dedup-key data]}] (SUT/inbound-payment-held
                                      {:EndToEndTransactionId "NOTPROVIDED"
                                       :TransactionAmount 500.29
                                       :Scheme "FasterPayments"
                                       :TimestampCreated "2026-04-01T12:00:00Z"
                                       :Account {:BBAN "04000412345678"}})]
      (is (= "NOTPROVIDED:04000412345678:50029:2026-04-01T12:00:00Z:held"
             dedup-key))
      (is (= 50029 (:amount data))))))

(deftest assessment-failure-spellings-test
  (let [instructions [{:EndToEndId "pmt.3" :Reasons ["Bad sort code"]}
                      {:EndToEndId "pmt.4" :Reasons ["Bad account"]}]]
    (doseq [k [:AssessmentFailure :AssesmentFailure]]
      (testing (str "the " (name k) " spelling fans out per instruction")
        (let [res (SUT/outbound-payment-assessment-failed {:MessageId "msg-1"
                                                           k instructions})]
          (is (not (error/anomaly? res)))
          (is (= ["pmt.3:rejected" "pmt.4:rejected"] (mapv :dedup-key res))))))
    (testing "neither key, or an empty list, refuses"
      (doseq [payload [{:MessageId "msg-2"}
                       {:MessageId "msg-2" :AssessmentFailure []}]]
        (let [res (SUT/outbound-payment-assessment-failed payload)]
          (is (error/rejection? res))
          (is (= :payment/invalid-assessment-payload (error/kind res)))
          (is (= "msg-2" (:message-id (error/payload res)))))))))

(deftest fps-body-amount-test
  (doseq [[minor-units rendered] [[29 "0.29"] [99999 "999.99"]]]
    (is (str/includes? (clearbank/->fps-body {:payment-id "pmt.5"
                                              :end-to-end-id "pmt.5"
                                              :debtor-bban "04000400000001"
                                              :creditor-bban "04000400000002"
                                              :creditor-name "Ford Prefect"
                                              :amount minor-units
                                              :currency "GBP"})
                       (str "\"instructedAmount\":" rendered))
        (str minor-units))))
