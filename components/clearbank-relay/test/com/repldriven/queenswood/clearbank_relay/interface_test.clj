(ns com.repldriven.queenswood.clearbank-relay.interface-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.clearbank-relay.store :as store]
    [com.repldriven.queenswood.clearbank-relay.interface :as SUT]
    [com.repldriven.queenswood.clearbank-relay.outbound :as outbound]
    [com.repldriven.queenswood.changelog-relay.interface]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.message-bus.interface :as message-bus]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(defn- event
  [outbox-id dedup-key]
  {:outbox-id outbox-id
   :dedup-key dedup-key
   :event-name "transaction-settled"
   :payload (.getBytes "avro-payload-bytes")
   :correlation-id "corr-1"
   :causation-id "caus-1"
   :created-at (utility/now)})

(deftest outbox-dedup-and-relay-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         bus (system/instance sys [:message-bus :bus])]
     (testing "a duplicate dedup-key is rejected by the unique index"
       (nom-test> [_ (SUT/save-event config (event "obx.1" "e2e-1:settled"))])
       (let [dup (SUT/save-event config (event "obx.2" "e2e-1:settled"))]
         (is (SUT/uniqueness-violation? dup)
             "a second save reusing the dedup-key must violate")))
     (testing "the relay publishes a stored event to the bus"
       (let [received (promise)
             handler (system/instance sys [:relay-handler :handler])]
         (message-bus/subscribe bus
                                :schemes-payments-event
                                (fn [e] (deliver received e)))
         (nom-test> [_ (SUT/save-event config (event "obx.3" "e2e-2:settled"))])
         (fdb/process-changelog (:record-db config)
                                "test-relay"
                                "clearbank-outbox"
                                handler
                                {:keyspace-prefix
                                 (system/instance sys [:fdb :keyspace-prefix])})
         (let [e (deref received 5000 ::timeout)]
           (is (not= ::timeout e) "relay must publish the stored event")
           (when (not= ::timeout e)
             (is (= "transaction-settled" (:event e)))
             (is (= "corr-1" (:correlation-id e))))))))))

(defn- intent-of
  [intent-id dedup-key]
  {:intent-id intent-id
   :dedup-key dedup-key
   :request "{\"paymentInstructions\":[]}"
   :status "pending"
   :attempts 0
   :created-at (utility/now)})

(deftest outbound-intent-queue-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}]
     (testing "a duplicate dedup-key (redelivered submit) is rejected"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.1" "e2e-A"))])
       (is (SUT/uniqueness-violation?
            (SUT/save-intent config (intent-of "int.2" "e2e-A")))))
     (testing "a sent intent leaves the pending work-queue"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.3" "e2e-B"))])
       (is (some #(= "int.3" (:intent-id %)) (store/pending-intents config)))
       (nom-test> [_ (store/mark-sent config "int.3")])
       (is (not (some #(= "int.3" (:intent-id %))
                      (store/pending-intents config)))))
     (testing "a failed POST keeps the intent pending and bumps its attempt"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.4" "e2e-C"))])
       (outbound/drain-once
        (assoc config :clearbank-url "http://localhost:1" :max-attempts 10)
        (utility/now))
       (let [i4 (first (filter #(= "int.4" (:intent-id %))
                               (store/pending-intents config)))]
         (is (some? i4) "still pending after an unreachable POST")
         (is (= 1 (:attempts i4)) "attempt count bumped"))))))

(defn- relay-config
  [sys post-fn]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])
   :schemas (system/instance sys [:avro :serde])
   :clearbank-url "http://scheme.invalid"
   :max-attempts 3
   :initial-backoff-ms 1000
   :max-backoff-ms 60000
   :post-fn post-fn})

(defn- answering
  [calls res]
  (fn [_url _request] (swap! calls inc) res))

(defn- fps-response
  [status end-to-end-id response]
  {:status status
   :headers {:content-type "application/json"}
   :body (str "{\"transactions\":[{\"endToEndIdentification\":\""
              end-to-end-id
              "\",\"response\":\""
              response
              "\"}],\"halLinks\":[]}")})

(defn- load-intent
  [config intent-id]
  (fdb/transact config
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open
                                            txn
                                            "clearbank-outbound-intents")
                                           intent-id)
                          schema/pb->ClearbankOutboundIntent))))

(defn- submission-rejections
  [config dedup-key]
  (fdb/transact config
                (fn [txn]
                  (mapv schema/pb->ClearbankOutboxEvent
                        (fdb/query-records
                         (fdb/open txn "clearbank-outbox")
                         "ClearbankOutboxEvent"
                         "dedup_key"
                         (str dedup-key ":submission-rejected")
                         {:index "ClearbankOutboxEvent_by_dedup_key"})))))

(defn- decode-rejection
  [config event]
  (let [{:keys [schemas]} config]
    (avro/deserialize-same (get schemas "transaction-rejected")
                           (:payload event))))

(defn- assert-failed
  [config intent-id dedup-key cancellation-code]
  (let [intent (load-intent config intent-id)
        events (submission-rejections config dedup-key)
        rejection (some->> (first events)
                           (decode-rejection config))]
    (is (= "failed" (:status intent)))
    (is (= 1 (count events)) "one submission-rejected event")
    (is (= "transaction-rejected" (:event-name (first events))))
    (is (= dedup-key (:end-to-end-id rejection)))
    (is (= cancellation-code (:cancellation-code rejection)))
    (is (= :debit-credit-code-debit (:debit-credit-code rejection)))
    (is (false? (:is-return rejection)))))

(deftest outbound-relay-backoff-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [calls (atom 0)
         config (relay-config sys (answering calls (fps-response 500 nil nil)))
         t0 1700000000000]
     (nom-test> [_ (SUT/save-intent config (intent-of "int.5" "e2e-D"))])
     (testing "a 500 retries with the delay doubling from its initial value"
       (outbound/drain-once config t0)
       (is (= 1 @calls))
       (is (= (+ t0 1000) (:next-attempt-at (load-intent config "int.5"))))
       (outbound/drain-once config t0)
       (is (= 1 @calls) "not relayed again before its next attempt")
       (outbound/drain-once config (+ t0 1000))
       (is (= 2 @calls))
       (is (= (+ t0 3000) (:next-attempt-at (load-intent config "int.5"))))
       (outbound/drain-once config (+ t0 2999))
       (is (= 2 @calls) "not relayed again before its next attempt"))
     (testing "a 500 at the last attempt fails the intent"
       (outbound/drain-once config (+ t0 3000))
       (is (= 3 @calls))
       (assert-failed config "int.5" "e2e-D" "CB_SubmissionFailed")
       (outbound/drain-once config (+ t0 60000))
       (is (= 3 @calls) "a failed intent is not relayed")))))

(deftest outbound-relay-refusal-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [config (relay-config sys nil)
         relay (fn [res] (assoc config :post-fn (fn [_url _request] res)))
         now 1700000000000]
     (testing "a 400 fails the intent on its first attempt"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.6" "e2e-E"))])
       (outbound/drain-once (relay (fps-response 400 nil nil)) now)
       (assert-failed config "int.6" "e2e-E" "CB_SubmissionRefused"))
     (testing "a 202 whose transaction is Rejected fails the intent"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.7" "e2e-F"))])
       (outbound/drain-once (relay (fps-response 202 "e2e-F" "Rejected")) now)
       (assert-failed config "int.7" "e2e-F" "CB_SubmissionRefused"))
     (testing "a 202 with no transaction for the intent fails the intent"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.8" "e2e-G"))])
       (outbound/drain-once (relay (fps-response 202 "e2e-other" "Accepted"))
                            now)
       (assert-failed config "int.8" "e2e-G" "CB_SubmissionRefused"))
     (testing "a 202 whose transaction is Accepted marks the intent sent"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.9" "e2e-H"))])
       (outbound/drain-once (relay (fps-response 202 "e2e-H" "Accepted")) now)
       (is (= "sent" (:status (load-intent config "int.9"))))
       (is (empty? (submission-rejections config "e2e-H")))))))

(deftest fail-intent-once-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [config (relay-config sys nil)
         rejected (fn [outbox-id]
                    (assoc (event outbox-id "e2e-J:submission-rejected")
                           :event-name
                           "transaction-rejected"))]
     (nom-test> [_ (SUT/save-intent config (intent-of "int.10" "e2e-J"))])
     (testing "the first call fails the intent and writes the event"
       (nom-test> [failed
                   (store/fail-intent config "int.10" 4 (rejected "obx.10"))
                   _ (is (= "failed" (:status failed)))]))
     (testing "a second call writes nothing"
       (nom-test> [again
                   (store/fail-intent config "int.10" 5 (rejected "obx.11"))
                   _ (is (= 4 (:attempts again)))])
       (is (= "failed" (:status (load-intent config "int.10"))))
       (is (= ["obx.10"]
              (mapv :outbox-id (submission-rejections config "e2e-J"))))))))
