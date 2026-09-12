(ns com.repldriven.queenswood.clearbank-adapter.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.clearbank-adapter.interface :as SUT]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(def ^:dynamic *base-url* "http://localhost:{PORT}")

(defn- post
  [path body]
  (http/request
   {:method :post
    :url (str *base-url* path)
    :headers {"Content-Type" "application/json"}
    :body (json/write-str body)}))

(defn- test-transaction-settled-credit
  []
  (let [nonce 12345]
    (nom-test>
      [res (post "/webhooks/transaction-settled"
                 {:Type "TransactionSettled"
                  :Version 6
                  :Payload {:TransactionId "txn-001"
                            :Status "Settled"
                            :Scheme "FasterPayments"
                            :EndToEndTransactionId "e2e-001"
                            :Amount 100.00
                            :CurrencyCode "GBP"
                            :DebitCreditCode "Credit"
                            :TimestampSettled "2026-04-01T12:00:00Z"
                            :TimestampCreated "2026-04-01T12:00:00Z"
                            :Reference "Test payment"
                            :IsReturn false
                            :Account {}
                            :CounterpartAccount {}}
                  :Nonce nonce})
       _ (is (= 200 (:status res)))
       body (http/res->edn res)
       _ (is (= nonce (:Nonce body)))])))

(defn- test-transaction-settled-debit
  []
  (let [nonce 12346]
    (nom-test>
      [res (post "/webhooks/transaction-settled"
                 {:Type "TransactionSettled"
                  :Version 6
                  :Payload {:TransactionId "txn-002"
                            :Status "Settled"
                            :Scheme "FasterPayments"
                            :EndToEndTransactionId "e2e-002"
                            :Amount 50.00
                            :CurrencyCode "GBP"
                            :DebitCreditCode "Debit"
                            :TimestampSettled "2026-04-01T12:00:00Z"
                            :TimestampCreated "2026-04-01T12:00:00Z"
                            :Reference "Outbound payment"
                            :IsReturn false
                            :Account {}
                            :CounterpartAccount {}}
                  :Nonce nonce})
       _ (is (= 200 (:status res)))
       body (http/res->edn res)
       _ (is (= nonce (:Nonce body)))])))

(defn- test-transaction-rejected
  []
  (let [nonce 12347]
    (nom-test>
      [res (post "/webhooks/transaction-rejected"
                 {:Type "TransactionRejected"
                  :Version 2
                  :Payload {:TransactionId "txn-003"
                            :Status "Rejected"
                            :Scheme "FasterPayments"
                            :EndToEndTransactionId "e2e-003"
                            :CancellationCode "AM09"
                            :CancellationReason "Insufficient funds"
                            :DebitCreditCode "Debit"
                            :IsReturn false
                            :Account {}
                            :CounterpartAccount {}}
                  :Nonce nonce})
       _ (is (= 200 (:status res)))
       body (http/res->edn res)
       _ (is (= nonce (:Nonce body)))])))

(defn- test-payment-message-assessment-failed
  []
  (let [nonce 12348]
    (nom-test>
      [res (post "/webhooks/payment-message-assessment-failed"
                 {:Type "PaymentMessageAssessmentFailed"
                  :Version 1
                  :Payload {:MessageId "msg-001"
                            :AssessmentFailure
                            [{:EndToEndId "e2e-004"
                              :Reasons ["Invalid sort code"]}]}
                  :Nonce nonce})
       _ (is (= 200 (:status res)))
       body (http/res->edn res)
       _ (is (= nonce (:Nonce body)))])))

(defn- test-inbound-held-transaction
  []
  (let [nonce 12349]
    (nom-test>
      [res (post "/webhooks/inbound-held-transaction"
                 {:Type "InboundHeldTransaction"
                  :Version 1
                  :Payload {:EndToEndTransactionId "e2e-005"
                            :TransactionAmount 500.00
                            :Scheme "FasterPayments"
                            :Account {:BBAN "12345678"}
                            :TimestampCreated "2026-04-01T12:00:00Z"}
                  :Nonce nonce})
       _ (is (= 200 (:status res)))
       body (http/res->edn res)
       _ (is (= nonce (:Nonce body)))])))

(deftest clearbank-adapter-test
  (with-test-system [sys
                     ["classpath:clearbank-adapter/application-test.yml"
                      #(assoc-in % [:system/defs :server :handler] SUT/app)]]
                    (let [jetty (system/instance sys [:server :jetty-adapter])]
                      (binding [*base-url* (server/http-local-url jetty)]
                        (testing "TransactionSettled credit dispatches inbound"
                          (test-transaction-settled-credit))
                        (testing "TransactionSettled debit dispatches outbound"
                          (test-transaction-settled-debit))
                        (testing "TransactionRejected echoes nonce"
                          (test-transaction-rejected))
                        (testing "PaymentMessageAssessmentFailed echoes nonce"
                          (test-payment-message-assessment-failed))
                        (testing "InboundHeldTransaction echoes nonce"
                          (test-inbound-held-transaction))))))
