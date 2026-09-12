(ns com.repldriven.queenswood.payee-check.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.payee-check.commands :as commands]
    [com.repldriven.queenswood.payee-check.interface]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(deftest unknown-command-test
  (testing "dispatch rejects command names not in the handler registry"
    (let [result (#'commands/dispatch
                  {:schemas {}}
                  {:command "unknown-payee-command" :payload nil})]
      (is (error/rejection? result))
      (is (= :payee-check/unknown-command (error/kind result))))))

(def ^:private test-bank-id "bnk_test_payee")

(defn- send-command
  [proc schemas command-name data]
  (let [payload (avro/serialize (get schemas command-name) data)]
    (if (error/anomaly? payload)
      payload
      (processor/process proc {:command command-name :payload payload}))))

(defn- decode-payload
  [schemas schema-name result]
  (avro/deserialize-same (get schemas schema-name) (:payload result)))

(defn- test-check-payee-unavailable
  [proc schemas]
  (testing "check-payee persists and replies even when the CoP adapter is down"
    (let [payload {:bank-id test-bank-id
                   :creditor-name "Ada Lovelace"
                   :account {:sort-code "123456" :account-number "12345678"}
                   :account-type :account-type-personal}]
      (nom-test> [result (send-command proc schemas "check-payee" payload)
                  _ (is (= "ACCEPTED" (:status result)))
                  decoded (decode-payload schemas "payee-check" result)
                  _ (is (some? (:check-id decoded)))
                  _ (is (= test-bank-id (:bank-id decoded)))
                  _ (is (= :match-result-unavailable
                           (-> decoded
                               :result
                               :match-result)))
                  _ (is (= "ACNS"
                           (-> decoded
                               :result
                               :reason-code)))
                  _ (is (= "Ada Lovelace"
                           (-> decoded
                               :request
                               :creditor-name)))]))))

(deftest process-check-payee-test
  (with-test-system [sys "classpath:payee-check/application-test.yml"]
                    (let [proc (system/instance sys [:payee-checks :processor])
                          schemas (system/instance sys [:avro :serde])]
                      (test-check-payee-unavailable proc schemas))))
