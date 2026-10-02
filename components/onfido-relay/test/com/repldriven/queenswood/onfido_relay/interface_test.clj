(ns com.repldriven.queenswood.onfido-relay.interface-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.onfido-relay.store :as store]
    [com.repldriven.queenswood.onfido-relay.interface :as SUT]
    [com.repldriven.queenswood.onfido-relay.outbound :as outbound]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(defn- event
  [outbox-id dedup-key]
  {:outbox-id outbox-id
   :dedup-key dedup-key
   :event-name "idv-evidence"
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
                     :party-id "pty.1"
                     :first-name "Ada"
                     :middle-names nil
                     :last-name "Lovelace"
                     :date-of-birth nil
                     :address nil
                     :session-id nil
                     :channel nil
                     :return-url nil
                     :email nil
                     :verifications []
                     :screenings []})
   :status "pending"
   :attempts 0
   :created-at (utility/now)})

(deftest outbox-and-intent-test
  (with-test-system
   [sys "classpath:onfido-relay/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}]
     (testing "a duplicate outbox dedup-key is rejected"
       (nom-test> [_ (SUT/save-event config (event "obx.1" "iv-1:completed"))])
       (is (SUT/uniqueness-violation?
            (SUT/save-event config (event "obx.2" "iv-1:completed")))))
     (testing "a duplicate intent dedup-key is rejected"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.1" "iv-A"))])
       (is (SUT/uniqueness-violation?
            (SUT/save-intent config (intent-of "int.2" "iv-A")))))
     (testing "a failed submit keeps the intent pending and bumps its attempt"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.3" "iv-B"))])
       (outbound/drain-once (assoc config
                                   :onfido-url "http://localhost:1"
                                   :workflows
                                   [{:id "wf" :verifies [] :screens []}]
                                   :max-attempts 10))
       (let [i3 (first (filter #(= "int.3" (:intent-id %))
                               (store/pending-intents config)))]
         (is (some? i3) "still pending after an unreachable submit")
         (is (= 1 (:attempts i3)) "attempt count bumped"))))))
