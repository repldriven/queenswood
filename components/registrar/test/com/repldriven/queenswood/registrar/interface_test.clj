(ns ^:eftest/synchronized com.repldriven.queenswood.registrar.interface-test
  "A test adapter registered against one provider atom, which both
  tests reset, so they run one at a time."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.registrar.interface :as SUT]

    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private provider
  "The provider a test adapter subscribes at: whether it answers, and
  the subscriptions it holds."
  (atom {:up? true :held #{}}))

(SUT/defsubscriptions
 :registrar-test
 {:wanted (fn [_config] [:opened :closed])
  :held (fn [_config]
          (let [{:keys [up? held]} @provider]
            (if up? [:answered held] [:retry "provider down"])))
  :subscribe (fn [_config subscription]
               (if (:up? @provider)
                 (do (swap! provider update :held conj subscription)
                     [:answered subscription])
                 [:retry "provider down"]))})

(def ^:private delivery-policy
  {:default {:initial-backoff-ms 100
             :backoff-growth 2
             :max-backoff-ms 500
             :max-attempts 3
             :max-age-ms 3600000}
   :breaker {:failure-threshold 1
             :cool-down-ms 200
             :max-cool-down-ms 400
             :probe-lease-ms 30000}})

(defn- registrar-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])
   :delivery-policy delivery-policy
   :retry-ms 20
   :check-ms 20
   :readiness (atom false)})

(defn- eventually
  [pred]
  (loop [n 0]
    (cond
     (pred)
     true

     (< n 200)
     (do (Thread/sleep 20) (recur (inc n)))

     :else
     false)))

(deftest ensure-subscribed-test
  (with-test-system
   [sys "classpath:registrar/application-test.yml"]
   (let [config (registrar-config sys)]
     (testing "a provider that does not answer is a failure, and not ready"
       (reset! provider {:up? false :held #{}})
       (is (= :failed (SUT/ensure-subscribed :registrar-test config)))
       (is (false? @(:readiness config))))
     (testing "each subscription the provider lacks is made, and it is ready"
       (reset! provider {:up? true :held #{}})
       (is (= :held (SUT/ensure-subscribed :registrar-test config)))
       (is (= #{:opened :closed} (:held @provider)))
       (is (true? @(:readiness config))))
     (testing "a subscription the provider forgot is made again"
       (swap! provider update :held disj :opened)
       (is (= :held (SUT/ensure-subscribed :registrar-test config)))
       (is (= #{:opened :closed} (:held @provider)))))))

(deftest registrar-probes-the-breaker-test
  (with-test-system
   [sys "classpath:registrar/application-test.yml"]
   (let [config (registrar-config sys)
         breaker (fn []
                   (:state (circuit-breaker/breaker config
                                                    "adapter:registrar-test")))]
     (reset! provider {:up? false :held #{}})
     (let [{:keys [stop]} (SUT/start :registrar-test config)]
       (try (testing "a provider down at start-up opens the adapter's breaker"
              (is (eventually (fn [] (= "open" (breaker)))))
              (is (false? @(:readiness config))))
            (testing
              "once it answers, it is subscribed to and the breaker closes"
              (swap! provider assoc :up? true)
              (is (eventually (fn [] @(:readiness config))))
              (is (eventually (fn [] (= "closed" (breaker)))))
              (is (= #{:opened :closed} (:held @provider))))
            (finally (stop)))))))
