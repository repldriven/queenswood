(ns com.repldriven.queenswood.onfido-relay.interface-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.onfido-relay.store :as store]
    [com.repldriven.queenswood.onfido-relay.interface :as SUT]
    [com.repldriven.queenswood.onfido-relay.outbound :as outbound]
    [com.repldriven.queenswood.changelog-relay.interface]

    [com.repldriven.queenswood.fdb.interface :as fdb]

    [com.repldriven.mono.message-bus.interface :as message-bus]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(deftest external-id-round-trip-test
  (is (= {:bank-id "bnk.1" :verification-id "iv.1"}
         (SUT/parse-external-id (outbound/composite-external-id "bnk.1"
                                                                "iv.1"))))
  (is (nil? (SUT/parse-external-id "no-separator"))))

(defn- event
  [outbox-id dedup-key]
  {:outbox-id outbox-id
   :dedup-key dedup-key
   :event-name "idv-completed"
   :payload (.getBytes "avro-payload-bytes")
   :correlation-id "corr-1"
   :causation-id "caus-1"
   :created-at (utility/now)})

(defn- intent-of
  [intent-id dedup-key]
  {:intent-id intent-id
   :dedup-key dedup-key
   :request (pr-str {:bank-id "bnk.1"
                     :verification-id dedup-key
                     :first-name "Ada"
                     :last-name "Lovelace"})
   :status "pending"
   :attempts 0
   :created-at (utility/now)})

(deftest outbox-and-intent-test
  (with-test-system
   [sys "classpath:onfido-relay/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         bus (system/instance sys [:message-bus :bus])]
     (testing "a duplicate outbox dedup-key is rejected"
       (nom-test> [_ (SUT/save-event config (event "obx.1" "iv-1:completed"))])
       (is (SUT/uniqueness-violation?
            (SUT/save-event config (event "obx.2" "iv-1:completed")))))
     (testing "the relay publishes a stored event to the idv-event channel"
       (let [received (promise)
             handler (system/instance sys [:relay-handler :handler])]
         (message-bus/subscribe bus :idv-event (fn [e] (deliver received e)))
         (nom-test> [_ (SUT/save-event config (event "obx.3" "iv-2:completed"))])
         (fdb/process-changelog (:record-db config)
                                "test-relay"
                                "onfido-outbox"
                                handler
                                {:keyspace-prefix
                                 (system/instance sys [:fdb :keyspace-prefix])})
         (let [e (deref received 5000 ::timeout)]
           (is (not= ::timeout e))
           (when (not= ::timeout e) (is (= "idv-completed" (:event e)))))))
     (testing "a duplicate intent dedup-key is rejected"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.1" "iv-A"))])
       (is (SUT/uniqueness-violation?
            (SUT/save-intent config (intent-of "int.2" "iv-A")))))
     (testing "a failed submit keeps the intent pending and bumps its attempt"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.3" "iv-B"))])
       (outbound/drain-once
        (assoc config :onfido-url "http://localhost:1" :max-attempts 10))
       (let [i3 (first (filter #(= "int.3" (:intent-id %))
                               (store/pending-intents config)))]
         (is (some? i3) "still pending after an unreachable submit")
         (is (= 1 (:attempts i3)) "attempt count bumped"))))))
