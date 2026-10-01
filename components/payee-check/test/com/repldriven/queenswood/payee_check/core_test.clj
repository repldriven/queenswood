(ns com.repldriven.queenswood.payee-check.core-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.payee-check.core :as SUT]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private test-bank-id "bnk_test_payee")

(def ^:private unreachable-adapter
  "An adapter address nothing listens on, so a check takes the path where
  the provider cannot answer."
  {:modulr "http://localhost:1"})

(defn- config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :meta-store])
   :adapter-urls unreachable-adapter})

(def ^:private request
  {:bank-id test-bank-id
   :creditor-name "Ada Lovelace"
   :account {:sort-code "123456" :account-number "12345678"}
   :account-type :account-type-personal})

(deftest a-check-the-adapter-cannot-answer-is-saved-unavailable-test
  (with-test-system
   [sys "classpath:payee-check/application-test.yml"]
   (let [config (config sys)]
     (nom-test> [check (SUT/check-and-save config request)
                 _ (testing "the check records the answer it could not get"
                     (is (some? (:check-id check)))
                     (is (= test-bank-id (:bank-id check)))
                     (is (= :match-result-unavailable
                            (get-in check [:result :match-result])))
                     (is (= "ACNS" (get-in check [:result :reason-code])))
                     (is (= "Ada Lovelace"
                            (get-in check [:request :creditor-name]))))
                 saved (SUT/get-check config test-bank-id (:check-id check))
                 _ (testing "and reads back as saved"
                     (is (= (:check-id check) (:check-id saved))))]))))

(deftest a-check-from-an-account-the-bank-does-not-hold-is-refused-test
  (with-test-system
   [sys "classpath:payee-check/application-test.yml"]
   (let [result (SUT/check-and-save
                 (config sys)
                 (assoc request :account-id "acc.01kprbmgcj35ptc8npmybhh4sa"))]
     (is (error/rejection? result))
     (is (= :cash-account/not-found (error/kind result))))))
