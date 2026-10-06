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

    [clojure.test :refer [deftest is testing]])
  (:import
    (java.util.concurrent Executors)))

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

(SUT/defoperations :poller-concurrent
                   {"call" {:call respond :answered answered :failed failed}})

(defn- respond-by-id
  "The external API's answer to one intent, as the test's `answers` atom
  holds it by intent id, an answer for any it does not name."
  [config _now intent]
  (get @(:answers config) (:intent-id intent) [:answered nil]))

(SUT/defoperations :poller-rounds
                   {"call"
                    {:call respond-by-id :answered answered :failed failed}})

(SUT/defoperations :poller-limit
                   {"call" {:call respond :answered answered :failed failed}})

(defn- reconciled
  [_config _now _intent]
  {:status "settled"})

(SUT/defoperations
 :poller-reconcile
 {"call"
  {:call respond :answered answered :failed failed :reconcile reconciled}})

(SUT/defoperations :poller-unread-sent
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

(deftest concurrent-test
  (with-test-system
   [sys "classpath:intent-poller/application-test.yml"]
   (let [answers (atom [:answered nil])
         executor (Executors/newFixedThreadPool 4)
         config (assoc (poller-config sys :poller-concurrent answers)
                       :executor
                       executor)
         spec (spec :poller-concurrent)
         t0 1000000
         status (fn [id] (:status (by-id config id)))]
     (try
       (nom-test> [_ (SUT/save-intent
                      config
                      spec
                      (assoc (intent "con.1" t0) :subjects ["a"]))
                   _ (SUT/save-intent
                      config
                      spec
                      (assoc (intent "con.2" t0) :subjects ["b"]))
                   _ (SUT/save-intent
                      config
                      spec
                      (assoc (intent "con.3" t0) :subjects ["a"]))
                   _ (SUT/save-intent
                      config
                      spec
                      (assoc (intent "con.4" t0) :subjects ["c"]))])
       (testing
         "intents for different subjects run at once, and a later intent
             for a subject in the next round of the same pass"
         (is (= 4 (SUT/drain-once config t0)))
         (is (= ["settled" "settled" "settled" "settled"]
                (mapv status ["con.1" "con.2" "con.3" "con.4"]))))
       (testing "a pass with nothing to do runs nothing"
         (is (= 0 (SUT/drain-once config (+ t0 1)))))
       (finally (.shutdown executor))))))


(deftest rounds-keep-order-test
  (with-test-system
   [sys "classpath:intent-poller/application-test.yml"]
   (let [answers (atom {"rnd.1" [:retry "not yet"]})
         executor (Executors/newFixedThreadPool 4)
         config
         (assoc (poller-config sys :poller-rounds answers) :executor executor)
         spec (spec :poller-rounds)
         t0 1000000
         by (fn [id] (by-id config id))]
     (try (nom-test> [_ (SUT/save-intent
                         config
                         spec
                         (assoc (intent "rnd.1" t0) :subjects ["a"]))
                      _ (SUT/save-intent
                         config
                         spec
                         (assoc (intent "rnd.2" t0) :subjects ["a"]))
                      _ (SUT/save-intent
                         config
                         spec
                         (assoc (intent "rnd.3" t0) :subjects ["b"]))])
          (SUT/drain-once config t0)
          (testing "an intent left pending holds a later one for its subject"
            (is (= 1 (:attempts (by "rnd.1"))))
            (is (= ["pending" 0] ((juxt :status :attempts) (by "rnd.2")))))
          (testing "while another subject's runs"
            (is (= "settled" (:status (by "rnd.3")))))
          (finally (.shutdown executor))))))

(deftest pass-limit-test
  (with-test-system
   [sys "classpath:intent-poller/application-test.yml"]
   (let [answers (atom [:answered nil])
         executor (Executors/newFixedThreadPool 4)
         config (assoc (poller-config sys :poller-limit answers)
                       :executor executor
                       :pass-limit 2)
         spec (spec :poller-limit)
         t0 1000000
         status (fn [id] (:status (by-id config id)))]
     (try (nom-test> [_ (SUT/save-intent
                         config
                         spec
                         (assoc (intent "lim.3" t0) :subjects ["c"]))
                      _ (SUT/save-intent
                         config
                         spec
                         (assoc (intent "lim.1" t0) :subjects ["a"]))
                      _ (SUT/save-intent
                         config
                         spec
                         (assoc (intent "lim.2" t0) :subjects ["b"]))])
          (testing "a pass reads only the oldest intents up to its limit"
            (is (= 2 (SUT/drain-once config t0)))
            (is (= ["settled" "settled" "pending"]
                   (mapv status ["lim.1" "lim.2" "lim.3"]))))
          (testing "and the next pass the rest"
            (is (= 1 (SUT/drain-once config (+ t0 1))))
            (is (= "settled" (status "lim.3"))))
          (finally (.shutdown executor))))))

(deftest unread-sent-test
  (with-test-system
   [sys "classpath:intent-poller/application-test.yml"]
   (let [answers (atom [:answered nil])
         executor (Executors/newFixedThreadPool 4)
         config (assoc (poller-config sys :poller-unread-sent answers)
                       :executor executor
                       :pass-limit 2
                       :settles-first? (fn [i] (= "uns.4" (:intent-id i))))
         spec (spec :poller-unread-sent)
         t0 1000000
         save (fn [id status subjects]
                (SUT/save-intent
                 config
                 spec
                 (assoc (intent id t0) :status status :subjects subjects)))
         status (fn [id] (:status (by-id config id)))]
     (try (nom-test> [_ (save "uns.1" "sent" ["a"])
                      _ (save "uns.2" "sent" ["b"])
                      _ (save "uns.3" "pending" ["c"])
                      _ (save "uns.4" "pending" ["d"])
                      _ (save "uns.5" "pending" ["d"])])
          (testing "a full read of sent intents holds only what settles first"
            (is (= 1 (SUT/drain-once config t0)))
            (is (= ["settled" "pending" "pending"]
                   (mapv status ["uns.3" "uns.4" "uns.5"]))))
          (finally (.shutdown executor))))))

(deftest reconcile-concurrently-test
  (with-test-system
   [sys "classpath:intent-poller/application-test.yml"]
   (let [answers (atom [:answered nil])
         executor (Executors/newFixedThreadPool 4)
         config (assoc (poller-config sys :poller-reconcile answers)
                       :executor
                       executor)
         spec (spec :poller-reconcile)
         t0 1000000
         save (fn [id]
                (SUT/save-intent
                 config
                 spec
                 (assoc (intent id t0) :status "sent" :subjects [id])))
         status (fn [id] (:status (by-id config id)))]
     (try (nom-test> [_ (save "rec.1") _ (save "rec.2") _ (save "rec.3")])
          (testing "a pass reconciles every due sent intent on its workers"
            (SUT/drain-once config t0)
            (is (= ["settled" "settled" "settled"]
                   (mapv status ["rec.1" "rec.2" "rec.3"]))))
          (finally (.shutdown executor))))))

(deftest ordering-key-test
  (testing "a payment's events are keyed by the payment"
    (is (= "pmt.1" (SUT/ordering-key {:end-to-end-id "pmt.1" :amount 100}))))
  (testing "a transfer's events by the transfer"
    (is (= "trf.1" (SUT/ordering-key {:transfer-id "trf.1"}))))
  (testing "anything else is published unkeyed"
    (is (nil? (SUT/ordering-key {:account-id "acc.1"})))))
