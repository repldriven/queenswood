(ns com.repldriven.queenswood.intent-poller.interface-test
  "The poller's passes against an adapter's breaker: an outage opening
  it, a probe closing it, and an intent outliving its maximum age; and a
  store spec's redaction of a request once its intent is done."
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.intent-poller.interface :as SUT]

    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.util.concurrent Executors)))

(defn- respond
  "The external API's answer, as the test's `answers` atom holds it."
  [config _now _intent]
  @(:answers config))

(defn- answered
  [_config _now _intent _result]
  {:status :outbound-intent-status-settled})

(defn- failed
  [_config _now _intent _failure _reason]
  {:status :outbound-intent-status-failed})

(SUT/defoperations :poller-outage
                   {:zyphe-outbound-intent-kind-check
                    {:call respond :answered answered :failed failed}})

(SUT/defoperations :poller-expiry
                   {:zyphe-outbound-intent-kind-check
                    {:call respond :answered answered :failed failed}})

(SUT/defoperations :poller-concurrent
                   {:zyphe-outbound-intent-kind-check
                    {:call respond :answered answered :failed failed}})

(defn- respond-by-id
  "The external API's answer to one intent, as the test's `answers` atom
  holds it by intent id, an answer for any it does not name."
  [config _now intent]
  (get @(:answers config) (:intent-id intent) [:answered nil]))

(SUT/defoperations :poller-rounds
                   {:zyphe-outbound-intent-kind-check
                    {:call respond-by-id :answered answered :failed failed}})

(SUT/defoperations :poller-limit
                   {:zyphe-outbound-intent-kind-check
                    {:call respond :answered answered :failed failed}})

(defn- reconciled
  [_config _now _intent]
  {:status :outbound-intent-status-settled})

(SUT/defoperations
 :poller-reconcile
 {:zyphe-outbound-intent-kind-check
  {:call respond :answered answered :failed failed :reconcile reconciled}})

(defn- unreachable
  [_config now _intent]
  {:status :outbound-intent-status-sent
   :changes {:next-attempt-at (+ now 60000)}
   :outcome :retry})

(SUT/defoperations
 :poller-reconcile-outage
 {:zyphe-outbound-intent-kind-check
  {:call respond :answered answered :failed failed :reconcile unreachable}})

(SUT/defoperations :poller-wake
                   {:zyphe-outbound-intent-kind-check
                    {:call respond :answered answered :failed failed}})

(SUT/defoperations :poller-unread-sent
                   {:zyphe-outbound-intent-kind-check
                    {:call respond :answered answered :failed failed}})

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
   :delivery-policy delivery-policy
   :answers answers})

(defn- intent
  [intent-id created-at]
  {:intent-id intent-id
   :idempotency-key intent-id
   :kind :zyphe-outbound-intent-kind-check
   :request "{}"
   :status :outbound-intent-status-pending
   :attempt-count 0
   :created-at created-at})

(defn- by-id
  [config intent-id]
  (some (fn [i] (when (= intent-id (:intent-id i)) i))
        (concat (SUT/intents-with-status config :outbound-intent-status-pending)
                (SUT/intents-with-status config :outbound-intent-status-settled)
                (SUT/intents-with-status config
                                         :outbound-intent-status-failed))))

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
       (is (= 1 (:attempt-count (by-id config "out.1"))))
       (is (= 1 (:attempt-count (by-id config "out.2"))))
       (is (= 0 (:attempt-count (by-id config "out.3")))
           "the intent behind the opening keeps its attempts"))
     (testing "an open breaker calls nothing, and counts no attempt"
       (SUT/drain-once config (+ t0 2000))
       (is (= [1 1 0]
              (mapv (fn [id] (:attempt-count (by-id config id)))
                    ["out.1" "out.2" "out.3"]))))
     (testing "past the cool-down one call probes, and its answer closes it"
       (reset! answers [:answered nil])
       (SUT/drain-once config (+ t0 6000))
       (is (= :outbound-intent-status-settled (:status (by-id config "out.1"))))
       (is (= :outbound-intent-status-pending (:status (by-id config "out.2")))
           "one call only"))
     (testing "a closed breaker lets the rest through"
       (SUT/drain-once config (+ t0 7000))
       (is (= :outbound-intent-status-settled (:status (by-id config "out.2"))))
       (is (= :outbound-intent-status-settled
              (:status (by-id config "out.3"))))))))

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
       (is (= :outbound-intent-status-failed (:status (by-id config "exp.1"))))
       (is (= :outbound-intent-status-failed (:status (by-id config "exp.2"))))
       (is (= 1 (:attempt-count (by-id config "exp.2"))))))))

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
         (is (= [:outbound-intent-status-settled :outbound-intent-status-settled
                 :outbound-intent-status-settled
                 :outbound-intent-status-settled]
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
            (is (= 1 (:attempt-count (by "rnd.1"))))
            (is (= [:outbound-intent-status-pending 0]
                   ((juxt :status :attempt-count) (by "rnd.2")))))
          (testing "while another subject's runs"
            (is (= :outbound-intent-status-settled (:status (by "rnd.3")))))
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
            (is (= [:outbound-intent-status-settled
                    :outbound-intent-status-settled
                    :outbound-intent-status-pending]
                   (mapv status ["lim.1" "lim.2" "lim.3"]))))
          (testing "and the next pass the rest"
            (is (= 1 (SUT/drain-once config (+ t0 1))))
            (is (= :outbound-intent-status-settled (status "lim.3"))))
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
     (try (nom-test> [_ (save "uns.1" :outbound-intent-status-sent ["a"])
                      _ (save "uns.2" :outbound-intent-status-sent ["b"])
                      _ (save "uns.3" :outbound-intent-status-pending ["c"])
                      _ (save "uns.4" :outbound-intent-status-pending ["d"])
                      _ (save "uns.5" :outbound-intent-status-pending ["d"])])
          (testing "a full read of sent intents holds only what settles first"
            (is (= 1 (SUT/drain-once config t0)))
            (is (= [:outbound-intent-status-settled
                    :outbound-intent-status-pending
                    :outbound-intent-status-pending]
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
                (SUT/save-intent config
                                 spec
                                 (assoc (intent id t0)
                                        :status :outbound-intent-status-sent
                                        :subjects [id])))
         status (fn [id] (:status (by-id config id)))]
     (try (nom-test> [_ (save "rec.1") _ (save "rec.2") _ (save "rec.3")])
          (testing "a pass reconciles every due sent intent on its workers"
            (SUT/drain-once config t0)
            (is (= [:outbound-intent-status-settled
                    :outbound-intent-status-settled
                    :outbound-intent-status-settled]
                   (mapv status ["rec.1" "rec.2" "rec.3"]))))
          (finally (.shutdown executor))))))

(deftest reconcile-outage-test
  (with-test-system
   [sys "classpath:intent-poller/application-test.yml"]
   (let [answers (atom [:answered nil])
         config (poller-config sys :poller-reconcile-outage answers)
         spec (spec :poller-reconcile-outage)
         t0 1000000
         save (fn [id status]
                (SUT/save-intent
                 config
                 spec
                 (assoc (intent id t0) :status status :subjects [id])))]
     (nom-test> [_ (save "reo.1" :outbound-intent-status-sent)
                 _ (save "reo.2" :outbound-intent-status-sent)])
     (testing "lookups that go unanswered open the breaker"
       (SUT/drain-once config t0)
       (nom-test> [_ (save "reo.3" :outbound-intent-status-pending)])
       (is (= 0 (SUT/drain-once config (+ t0 100))))
       (is (= 0 (:attempt-count (by-id config "reo.3"))))))))

(deftest wake-test
  (with-test-system
   [sys "classpath:intent-poller/application-test.yml"]
   (let [answers (atom [:answered nil])
         config (assoc (poller-config sys :poller-wake answers) :poll-ms 60000)
         spec (spec :poller-wake)
         poller (SUT/start config)
         settled? (fn []
                    (= :outbound-intent-status-settled
                       (:status (by-id config "wake.1"))))]
     (try (Thread/sleep 500)
          (nom-test> [_ (SUT/save-intent config
                                         spec
                                         (intent "wake.1" (utility/now)))])
          (testing "a save wakes the idle poller rather than waiting poll-ms"
            (is (loop [n 0]
                  (cond (settled?)
                        true

                        (< n 40)
                        (do (Thread/sleep 50) (recur (inc n)))

                        :else
                        false))))
          (finally ((:stop poller)))))))

(defn- kept-bank-id
  [request]
  (pr-str (select-keys (edn/read-string request) [:bank-id])))

(deftest redact-test
  (with-test-system
   [sys "classpath:intent-poller/application-test.yml"]
   (let [config (poller-config sys :poller-redact (atom nil))
         redacting (assoc (spec :poller-redact) :redact kept-bank-id)
         request (pr-str {:bank-id "bnk.1" :email "arthur@example.test"})
         saved (fn [id spec]
                 (SUT/save-intent config
                                  spec
                                  (assoc (intent id 1000) :request request)))
         request-of (fn [id] (edn/read-string (:request (by-id config id))))]
     (nom-test> [_ (saved "red.1" redacting)
                 _ (saved "red.2" redacting)
                 _ (saved "red.3" (spec :poller-redact))
                 _ (SUT/advance config redacting "red.1" {:step 2} nil)])
     (testing "a pending intent keeps its whole request"
       (is (= "arthur@example.test" (:email (request-of "red.1")))))
     (nom-test> [_ (SUT/finish config
                               redacting
                               "red.1"
                               :outbound-intent-status-pending
                               :outbound-intent-status-settled
                               nil
                               nil)
                 _ (SUT/finish config
                               redacting
                               "red.2"
                               :outbound-intent-status-pending
                               :outbound-intent-status-failed
                               nil
                               nil)
                 _ (SUT/finish config
                               (spec :poller-redact)
                               "red.3"
                               :outbound-intent-status-pending
                               :outbound-intent-status-settled
                               nil
                               nil)])
     (testing "a settled or failed intent keeps only what the spec keeps"
       (is (= {:bank-id "bnk.1"} (request-of "red.1")))
       (is (= {:bank-id "bnk.1"} (request-of "red.2"))))
     (testing "a spec with no redaction keeps the request"
       (is (= "arthur@example.test" (:email (request-of "red.3"))))))))

(deftest ordering-key-test
  (testing "a payment's events are keyed by the payment"
    (is (= "pmt.1" (SUT/ordering-key {:end-to-end-id "pmt.1" :amount 100}))))
  (testing "a transfer's events by the transfer"
    (is (= "trf.1" (SUT/ordering-key {:transfer-id "trf.1"}))))
  (testing "anything else is published unkeyed"
    (is (nil? (SUT/ordering-key {:account-id "acc.1"})))))
