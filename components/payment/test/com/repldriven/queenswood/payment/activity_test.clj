(ns com.repldriven.queenswood.payment.activity-test
  (:require
    [com.repldriven.queenswood.payment.test-system]

    [com.repldriven.queenswood.payment.commands :as SUT]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.message-bus.interface :as message-bus]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.test :refer [deftest is testing]]))

(defn- capturing-bus
  "A bus whose Modulr producer records what it is sent and with what
  key."
  [sent]
  (let [record (fn [message opts] (swap! sent conj [message opts]) message)]
    {:producers {:modulr-command
                 (reify
                  message-bus/Producer
                    (send [_ message] (record message nil))
                    (send [_ message opts] (record message opts)))}}))

(defn- entry
  [schemas event-name data]
  {:event event-name
   :payload (avro/serialize (get schemas event-name) data)})

(deftest activity-is-sent-to-the-banks-provider-test
  (with-test-system
   [sys "classpath:payment/application-test.yml"]
   (let [sent (atom [])
         schemas (system/instance sys [:avro :serde])
         p (SUT/->ActivityEventProcessor
            {:record-db (system/instance sys [:fdb :record-db])
             :record-store (system/instance sys [:fdb :store])
             :schemas schemas
             :bus (capturing-bus sent)
             :payment-providers (system/instance sys
                                                 [:payment-provider
                                                  :providers])})
         handle (fn [event-name data]
                  ;; the brick's own event handler handed an envelope
                  ;; nosemgrep: brick-test-drives-pipeline
                  (processor/process p (entry schemas event-name data)))]
     (testing "an account's opening is sent as an open, keyed by its bank"
       (handle "account-opening"
               {:bank-id "bnk.a"
                :account-id "acc.1"
                :holder-name "Arthur Dent"
                :currency "GBP"
                :address-schemes ["scan"]})
       (let [[[message opts]] @sent
             data (avro/deserialize-same (get schemas "open-payment-account")
                                         (:payload message))]
         (is (= "open-payment-account" (:command message)))
         (is (= {:key "bnk.a"} opts))
         (is (= "acc.1" (:account-id data)))
         (is (= "Arthur Dent" (:holder-name data)))))
     (testing "a submitted payment is sent with its id as end-to-end id"
       (reset! sent [])
       (handle "outbound-payment-submitted"
               {:bank-id "bnk.a"
                :payment-id "pmt.1"
                :debtor-account-id "acc.1"
                :debtor-bban "04001000000001"
                :creditor-bban "20000012345678"
                :creditor-name "Ford Prefect"
                :amount 100
                :currency "GBP"})
       (let [[[message]] @sent
             data (avro/deserialize-same (get schemas "submit-payment")
                                         (:payload message))]
         (is (= "submit-payment" (:command message)))
         (is (= "pmt.1" (:end-to-end-id data)))
         (is (= "acc.1" (:debtor-account-id data)))))
     (testing
       "a parked inbound is not returned where the provider returns
               none"
       (reset! sent [])
       (handle "inbound-payment-suspended"
               {:bank-id "bnk.a"
                :payment-id "pmt.2"
                :end-to-end-id "e2e.2"
                :scheme-transaction-id "stx.2"
                :amount 100
                :currency "GBP"
                :reason-code "AC04"})
       (is (empty? @sent)))
     (testing "an entry no payment provider acts on sends nothing"
       (reset! sent [])
       (is (nil? (handle "idv-session-opening"
                         {:bank-id "bnk.a"
                          :verification-id "idv.1"
                          :party-id "pty.1"
                          :session-id "ses.1"
                          :verifications []
                          :screenings []})))
       (is (empty? @sent))))))
