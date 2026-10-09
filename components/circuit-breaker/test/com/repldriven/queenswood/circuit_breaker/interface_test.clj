(ns com.repldriven.queenswood.circuit-breaker.interface-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.circuit-breaker.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private policy
  {:failure-threshold 2
   :cool-down-ms 1000
   :max-cool-down-ms 4000
   :probe-lease-ms 500})

(deftest breaker-test
  (with-test-system
   [sys "classpath:circuit-breaker/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         destination "adapter:test"]
     (testing "a destination with no record is closed and has none"
       (nom-test> [decision (SUT/allow config policy destination 0)
                   _ (is (= :closed decision))
                   b (SUT/breaker config destination)
                   _ (is (nil? b))]))
     (testing "failures to the threshold open it, held in the store"
       (nom-test> [_ (SUT/record config policy destination :failed 10)
                   b (SUT/record config policy destination :failed 20)
                   _ (is (= :circuit-breaker-status-open (:status b)))
                   decision (SUT/allow config policy destination 500)
                   _ (is (= :open decision))]))
     (testing "past the cool-down one of two callers probes"
       (nom-test> [first-claim (SUT/allow config policy destination 1020)
                   second-claim (SUT/allow config policy destination 1021)
                   _ (is (= [:probe :open] [first-claim second-claim]))]))
     (testing "the probe's answer closes it"
       (nom-test> [b (SUT/record config policy destination :answered 1100)
                   _ (is (= :circuit-breaker-status-closed (:status b)))
                   _ (is (= 0 (:failure-count b)))])))))

(deftest guard-test
  (with-test-system
   [sys "classpath:circuit-breaker/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         destination "adapter:guarded"
         calls (atom 0)
         call (fn [result] (fn [] (swap! calls inc) result))
         outcome-of (fn [result] (if (= :down result) :failed :answered))
         policy (assoc policy :cool-down-ms 600000)]
     (testing "a closed breaker makes the call and returns its result"
       (is (= :up (SUT/guard config policy destination outcome-of (call :up))))
       (is (= 1 @calls)))
     (testing "failed calls to the threshold open it"
       (SUT/guard config policy destination outcome-of (call :down))
       (SUT/guard config policy destination outcome-of (call :down))
       (is (= :circuit-breaker-status-open
              (:status (SUT/breaker config destination)))))
     (testing "an open breaker answers at once, without calling"
       (let [res (SUT/guard config policy destination outcome-of (call :up))]
         (is (= :circuit-breaker/open (error/kind res)))
         (is (= 3 @calls)))))))

(defn- await-state
  [config destination state]
  (loop [n 0]
    (let [b (SUT/breaker config destination)]
      (cond
       (= state (:status b))
       true

       (< n 200)
       (do (Thread/sleep 20) (recur (inc n)))

       :else
       false))))

(deftest probe-test
  (with-test-system
   [sys "classpath:circuit-breaker/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         destination "adapter:probed"
         answer (atom :down)
         policy (assoc policy :cool-down-ms 200 :max-cool-down-ms 400)
         {:keys [stop]} (SUT/start-probe
                         config
                         policy
                         destination
                         {:probe (fn [] @answer)
                          :outcome-of (fn [res]
                                        (if (= :down res) :failed :answered))
                          :interval-ms (constantly 20)})]
     (try
       (testing "a destination that does not answer its probe opens"
         (is (await-state config destination :circuit-breaker-status-open)))
       (testing "once it answers, the next probe past the cool-down closes it"
         (reset! answer :up)
         (is (await-state config destination :circuit-breaker-status-closed)))
       (finally (stop))))))
