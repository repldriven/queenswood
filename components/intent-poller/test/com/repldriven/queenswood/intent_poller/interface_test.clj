(ns com.repldriven.queenswood.intent-poller.interface-test
  "The poller's passes against an adapter's breaker: an outage opening
  it, a probe closing it, and an intent outliving its maximum age."
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.intent-poller.interface :as SUT]

    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(defn- respond
  "The external API's answer, as the test's `answers` atom holds it."
  [config _now _intent]
  @(:answers config))

(defn- answered
  [_config _now _intent _result]
  {:status "settled"})

(defn- failed
  [_config _now _intent _failure _reason]
  {:status "failed"})

(SUT/defoperations :poller-outage
                   {"call" {:call respond :answered answered :failed failed}})

(SUT/defoperations :poller-expiry
                   {"call" {:call respond :answered answered :failed failed}})

(defn- spec
  [adapter]
  {:adapter adapter
   :outbox "zyphe-outbox"
   :intents "zyphe-outbound-intents"
   :intent-type "ZypheOutboundIntent"
   :event-type "ZypheOutboxEvent"
   :event->java schema/ZypheOutboxEvent->java
   :event->pb schema/ZypheOutboxEvent->pb
   :intent->java schema/ZypheOutboundIntent->java
   :pb->intent schema/pb->ZypheOutboundIntent})

(def ^:private delivery-policy
  {:default {:initial-backoff-ms 1000
             :backoff-growth 2
             :max-backoff-ms 1000
             :max-attempts 10
             :max-age-ms 60000}
   :breaker {:failure-threshold 2
             :cool-down-ms 5000
             :max-cool-down-ms 20000
             :probe-lease-ms 1000}})

(defn- poller-config
  [sys adapter answers]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])
   :adapter adapter
   :store (spec adapter)
   :default-operation "call"
   :delivery-policy delivery-policy
   :answers answers})

(defn- intent
  [intent-id created-at]
  {:intent-id intent-id
   :dedup-key intent-id
   :request "{}"
   :status "pending"
   :attempts 0
   :created-at created-at})

(defn- by-id
  [config intent-id]
  (some (fn [i] (when (= intent-id (:intent-id i)) i))
        (concat (SUT/intents-with-status config "pending")
                (SUT/intents-with-status config "settled")
                (SUT/intents-with-status config "failed"))))

(deftest outage-test
  (with-test-system
   [sys "classpath:intent-poller/application-test.yml"]
   (let [answers (atom [:retry "unreachable"])
         config (poller-config sys :poller-outage answers)
         t0 1000000]
     (nom-test> [_ (SUT/save-intent config
                                    (spec :poller-outage)
                                    (intent "out.1" t0))
                 _ (SUT/save-intent config
                                    (spec :poller-outage)
                                    (intent "out.2" t0))
                 _ (SUT/save-intent config
                                    (spec :poller-outage)
                                    (intent "out.3" t0))])
     (testing "failures to the threshold open the breaker mid-pass"
       (SUT/drain-once config t0)
       (is (= 1 (:attempts (by-id config "out.1"))))
       (is (= 1 (:attempts (by-id config "out.2"))))
       (is (= 0 (:attempts (by-id config "out.3")))
           "the intent behind the opening keeps its attempts"))
     (testing "an open breaker calls nothing, and counts no attempt"
       (SUT/drain-once config (+ t0 2000))
       (is (= [1 1 0]
              (mapv (fn [id] (:attempts (by-id config id)))
                    ["out.1" "out.2" "out.3"]))))
     (testing "past the cool-down one call probes, and its answer closes it"
       (reset! answers [:answered nil])
       (SUT/drain-once config (+ t0 6000))
       (is (= "settled" (:status (by-id config "out.1"))))
       (is (= "pending" (:status (by-id config "out.2"))) "one call only"))
     (testing "a closed breaker lets the rest through"
       (SUT/drain-once config (+ t0 7000))
       (is (= "settled" (:status (by-id config "out.2"))))
       (is (= "settled" (:status (by-id config "out.3"))))))))

(deftest expiry-test
  (with-test-system
   [sys "classpath:intent-poller/application-test.yml"]
   (let [answers (atom [:retry "unreachable"])
         config (assoc-in (poller-config sys :poller-expiry answers)
                 [:delivery-policy :breaker :cool-down-ms]
                 120000)
         t0 1000000]
     (nom-test> [_ (SUT/save-intent config
                                    (spec :poller-expiry)
                                    (intent "exp.1" t0))
                 _ (SUT/save-intent config
                                    (spec :poller-expiry)
                                    (intent "exp.2" t0))])
     (SUT/drain-once config t0)
     (testing "an intent past its maximum age fails while the breaker is open"
       (SUT/drain-once config (+ t0 60001))
       (is (= "failed" (:status (by-id config "exp.1"))))
       (is (= "failed" (:status (by-id config "exp.2"))))
       (is (= 1 (:attempts (by-id config "exp.2"))))))))
