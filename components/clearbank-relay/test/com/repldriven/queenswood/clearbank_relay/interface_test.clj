(ns com.repldriven.queenswood.clearbank-relay.interface-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.clearbank-relay.store :as store]
    [com.repldriven.queenswood.clearbank-relay.interface :as SUT]
    [com.repldriven.queenswood.clearbank-relay.outbound :as outbound]
    [com.repldriven.queenswood.changelog-relay.interface]

    [com.repldriven.queenswood.fdb.interface :as fdb]

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
        (assoc config :clearbank-url "http://localhost:1" :max-attempts 10))
       (let [i4 (first (filter #(= "int.4" (:intent-id %))
                               (store/pending-intents config)))]
         (is (some? i4) "still pending after an unreachable POST")
         (is (= 1 (:attempts i4)) "attempt count bumped"))))))
