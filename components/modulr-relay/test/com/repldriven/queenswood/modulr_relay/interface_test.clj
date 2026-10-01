(ns com.repldriven.queenswood.modulr-relay.interface-test
  (:require
    [com.repldriven.queenswood.modulr-relay.test-system]

    [com.repldriven.queenswood.modulr-relay.interface :as SUT]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(defn- event
  [outbox-id dedup-key event-name]
  {:outbox-id outbox-id
   :dedup-key dedup-key
   :event-name event-name
   :payload (.getBytes "avro-payload-bytes")
   :correlation-id "corr-1"
   :causation-id "caus-1"
   :created-at (utility/now)})

(defn- config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(deftest outbox-dedup-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [config (config sys)]
     (testing "a duplicate dedup-key is rejected by the unique index"
       (nom-test> [_ (SUT/save-event
                      config
                      (event "obx.1" "P1:settled" "transaction-settled"))])
       (is (SUT/uniqueness-violation?
            (SUT/save-event
             config
             (event "obx.2" "P1:settled" "transaction-settled"))))))))

(deftest intent-dedup-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [config (config sys)
         intent {:intent-id "int.1"
                 :dedup-key "pmt.1"
                 :kind "payment"
                 :request "{}"
                 :nonce "n-1"
                 :status "pending"
                 :attempts 0
                 :created-at (utility/now)}]
     (nom-test> [_ (SUT/save-intent config intent)])
     (testing "a redelivered command is a duplicate"
       (is (SUT/uniqueness-violation?
            (SUT/save-intent config (assoc intent :intent-id "int.2")))))
     (testing "the intent is found by its dedup key"
       (is (= "int.1" (:intent-id (SUT/find-intent config "pmt.1"))))
       (is (nil? (SUT/find-intent config "pmt.none")))))))

(deftest references-test
  (testing "a platform id survives the round trip through a reference"
    (is (= "pmt-01K6A3Z9X0" (SUT/->reference "pmt.01K6A3Z9X0")))
    (is (= "pmt.01K6A3Z9X0" (SUT/reference->id "pmt-01K6A3Z9X0")))))

(deftest outcomes-test
  (testing "a processed payment settles, keyed on the payment id"
    (is (= {:event-name "transaction-settled" :dedup-key "P1:settled"}
           (select-keys (SUT/payment-outcome {:provider-payment-id "P1"
                                              :end-to-end-id "pmt.1"
                                              :amount 100
                                              :currency "GBP"
                                              :status "PROCESSED"
                                              :at 0})
                        [:event-name :dedup-key]))))
  (testing "an error status declines"
    (let [{:keys [event-name data]} (SUT/payment-outcome {:provider-payment-id
                                                          "P1"
                                                          :end-to-end-id "pmt.1"
                                                          :status "ER_EXPIRED"
                                                          :at 0})]
      (is (= "transaction-rejected" event-name))
      (is (= :failure-kind-declined (:failure-kind data)))
      (is (= "NARR" (:reason-code data)))))
  (testing "a status that is not final reports nothing"
    (is (nil? (SUT/payment-outcome {:status "PENDING_FOR_FUNDS"})))
    (is (nil? (SUT/transfer-outcome {:status "SUBMITTED"}))))
  (testing "a transfer completes or fails"
    (is (= "transfer-completed"
           (:event-name (SUT/transfer-outcome {:provider-payment-id "P2"
                                               :transfer-id "ptr.1"
                                               :bank-id "bnk.1"
                                               :status "PROCESSED"
                                               :at 0}))))
    (is (= "transfer-failed"
           (:event-name (SUT/transfer-outcome {:provider-payment-id "P2"
                                               :transfer-id "ptr.1"
                                               :bank-id "bnk.1"
                                               :status "CANCELLED"
                                               :at 0}))))))
