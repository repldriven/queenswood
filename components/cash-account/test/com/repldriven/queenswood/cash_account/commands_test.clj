(ns com.repldriven.queenswood.cash-account.commands-test
  "What an unknown command name answers, and the envelope id arriving
  on the decoded data as `:idempotency-key`. The work each command
  then does is `interface-test`'s."
  (:require
    [com.repldriven.queenswood.cash-account.commands :as SUT]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file
  "classpath:cash-account/application-commands-test.yml")

(def ^:private test-bank-id "bnk.commands.dispatch")

(deftest the-envelope-id-becomes-the-idempotency-key-test
  (with-test-system
   [sys config-file]
   (let [schema ((system/instance sys [:avro :serde]) "open-cash-account")
         id "ik-dispatch-00000002"
         payload (avro/serialize schema
                                 {:bank-id test-bank-id
                                  :party-id "pty.commands.dispatch"
                                  :name "Dispatch Test Account"
                                  :currency "GBP"
                                  :product-id "prd.commands.dispatch"})]
     (testing "the decoded data carries the envelope id as its key"
       (let [data (#'SUT/decode schema {:id id :payload payload})]
         (is (= id (:idempotency-key data)))
         (is (= test-bank-id (:bank-id data))))))))

(deftest an-unknown-command-name-is-rejected-test
  (with-test-system
   [sys config-file]
   (let [schemas (system/instance sys [:avro :serde])
         processor (SUT/->CashAccountProcessor {:schemas schemas})]
     (testing "a name the table does not carry is rejected, not dropped"
       (let [result (processor/process processor
                                       {:command "not-a-cash-account-command"
                                        :id "ik-dispatch-00000003"
                                        :payload (byte-array 0)})]
         (is (error/rejection? result))
         (is (= :cash-account/unknown-command (error/kind result))))))))
