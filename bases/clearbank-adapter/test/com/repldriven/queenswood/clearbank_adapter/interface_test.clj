(ns com.repldriven.queenswood.clearbank-adapter.interface-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.clearbank-adapter.interface :as SUT]

    [com.repldriven.queenswood.fdb.interface :as fdb]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.processor.interface :as processor]
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

(defn- dedup-count
  [config store-name record-type dedup-keys]
  (fdb/transact config
                (fn [txn]
                  (let [store (fdb/open txn store-name)]
                    (reduce +
                            (map (fn [dedup-key]
                                   (count (fdb/query-records
                                           store
                                           record-type
                                           "dedup_key"
                                           dedup-key
                                           {:index (str record-type
                                                        "_by_dedup_key")})))
                                 dedup-keys))))))

(defn- outbox-count
  [config dedup-keys]
  (dedup-count config "clearbank-outbox" "ClearbankOutboxEvent" dedup-keys))

(defn- outbox-size
  [config]
  (fdb/transact config
                (fn [txn]
                  (count (:entries (fdb/scan-records
                                    (fdb/open txn "clearbank-outbox")
                                    {:limit 1000}))))))

(defn- settled-credit
  [transaction-id amount]
  {:Type "TransactionSettled"
   :Version 6
   :Payload {:TransactionId transaction-id
             :Status "Settled"
             :Scheme "FasterPayments"
             :EndToEndTransactionId "NOTPROVIDED"
             :Amount amount
             :CurrencyCode "GBP"
             :DebitCreditCode "Credit"
             :TimestampSettled "2026-04-01T12:00:00Z"
             :TimestampCreated "2026-04-01T12:00:00Z"
             :IsReturn false
             :Account {:BBAN "04000412345678"}
             :CounterpartAccount {}}
   :Nonce 12350})

(defn- test-inbound-settlements-sharing-end-to-end-id
  [config]
  (let [dedup-keys ["txn-101:settled" "txn-102:settled"]]
    (nom-test>
      [first-res (post "/webhooks/transaction-settled"
                       (settled-credit "txn-101" 0.29))
       _ (is (= 200 (:status first-res)))
       second-res (post "/webhooks/transaction-settled"
                        (settled-credit "txn-102" 0.29))
       _ (is (= 200 (:status second-res)))
       events (outbox-count config dedup-keys)
       _ (is (= 2 events))
       redelivered (post "/webhooks/transaction-settled"
                         (settled-credit "txn-101" 0.29))
       _ (is (= 200 (:status redelivered)))
       events-after (outbox-count config dedup-keys)
       _ (is (= 2 events-after))])))

(defn- test-settlement-with-third-decimal-place
  [config]
  (nom-test>
    [res (post "/webhooks/transaction-settled" (settled-credit "txn-103" 0.295))
     _ (is (= 400 (:status res)))
     body (http/res->edn res)
     _ (is (= {:type ":payment/invalid-scheme-amount"
               :title "REJECTED"
               :status 400}
              (select-keys body [:type :title :status])))
     events (outbox-count config ["txn-103:settled"])
     _ (is (zero? events))]))

(defn- assessment-failed
  [type payload]
  {:Type type
   :Version 1
   :Payload (merge {:MessageId "msg-002" :PaymentMethodType "FasterPayments"}
                   payload)
   :Nonce 12351})

(defn- test-assessment-failure-spellings
  [config]
  (doseq [[type k prefix] [["PaymentMessageAssessmentFailed" :AssessmentFailure
                            "e2e-201"]
                           ["PaymentMessageAssesmentFailed" :AssesmentFailure
                            "e2e-202"]]]
    (let [e2e-ids [(str prefix "a") (str prefix "b")]]
      (nom-test>
        [res (post "/webhooks/payment-message-assessment-failed"
                   (assessment-failed type
                                      {k (mapv (fn [e2e-id]
                                                 {:EndToEndId e2e-id
                                                  :Reasons
                                                  ["Invalid sort code"]})
                                               e2e-ids)}))
         _ (is (= 200 (:status res)) (name k))
         events (outbox-count config
                              (mapv (fn [e2e-id] (str e2e-id ":rejected"))
                                    e2e-ids))
         _ (is (= 2 events) (name k))]))))

(defn- test-assessment-failure-without-instructions
  [config]
  (nom-test>
    [before (outbox-size config)
     res (post "/webhooks/payment-message-assessment-failed"
               (assessment-failed "PaymentMessageAssessmentFailed" {}))
     _ (is (= 400 (:status res)))
     body (http/res->edn res)
     _ (is (= ":payment/invalid-assessment-payload" (:type body)))
     after (outbox-size config)
     _ (is (= before after))]))

(defn- test-submit-payment-twice
  [sys config]
  (let [proc (system/instance sys [:clearbank-adapter :command-processor])
        schemas (system/instance sys [:avro :serde])
        command {:payment-id "pmt.01kprbmgcj35ptc8npmybhh4s6"
                 :end-to-end-id "pmt.01kprbmgcj35ptc8npmybhh4s6"
                 :debtor-bban "04000400000001"
                 :creditor-bban "04000400000002"
                 :creditor-name "Ford Prefect"
                 :amount 29
                 :currency "GBP"}]
    (nom-test>
      [payload (avro/serialize (get schemas "submit-payment") command)
       message {:command "submit-payment" :payload payload}
       first-res (processor/process proc message)
       _ (is (= {:status "ACCEPTED"} first-res))
       second-res (processor/process proc message)
       _ (is (= {:status "ACCEPTED"} second-res))
       intents (dedup-count config
                            "clearbank-outbound-intents"
                            "ClearbankOutboundIntent"
                            [(:end-to-end-id command)])
       _ (is (= 1 intents))])))

(deftest clearbank-adapter-test
  (with-test-system
   [sys
    ["classpath:clearbank-adapter/application-test.yml"
     #(assoc-in % [:system/defs :server :handler] SUT/app)]]
   (let [jetty (system/instance sys [:server :jetty-adapter])
         config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :meta-store])}]
     (binding [*base-url* (server/http-local-url jetty)]
       (testing "TransactionSettled credit dispatches inbound"
         (test-transaction-settled-credit))
       (testing "TransactionSettled debit dispatches outbound"
         (test-transaction-settled-debit))
       (testing "TransactionRejected echoes nonce" (test-transaction-rejected))
       (testing "PaymentMessageAssessmentFailed echoes nonce"
         (test-payment-message-assessment-failed))
       (testing "InboundHeldTransaction echoes nonce"
         (test-inbound-held-transaction))
       (testing "inbound settlements sharing an end-to-end id are both kept"
         (test-inbound-settlements-sharing-end-to-end-id config))
       (testing "a settlement with a third decimal place answers 400"
         (test-settlement-with-third-decimal-place config))
       (testing "both assessment-failure spellings write one event each"
         (test-assessment-failure-spellings config))
       (testing "an assessment failure with no instructions answers 400"
         (test-assessment-failure-without-instructions config))
       (testing "a redelivered submit-payment enqueues one intent"
         (test-submit-payment-twice sys config))))))
