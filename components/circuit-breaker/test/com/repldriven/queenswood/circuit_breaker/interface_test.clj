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
       (nom-test> [decision (SUT/allow config policy destination 0 "r1")
                   _ (is (= :closed decision))
                   b (SUT/breaker config destination)
                   _ (is (nil? b))]))
     (testing "failures to the threshold open it, held in the store"
       (nom-test> [_ (SUT/record config policy destination :failed 10)
                   b (SUT/record config policy destination :failed 20)
                   _ (is (= "open" (:state b)))
                   decision (SUT/allow config policy destination 500 "r1")
                   _ (is (= :open decision))]))
     (testing "past the cool-down one of two claimants probes"
       (nom-test> [first-claim (SUT/allow config policy destination 1020 "r1")
                   second-claim (SUT/allow config policy destination 1021 "r2")
                   _ (is (= [:probe :open] [first-claim second-claim]))]))
     (testing "the probe's answer closes it"
       (nom-test> [b (SUT/record config policy destination :answered 1100)
                   _ (is (= "closed" (:state b)))
                   _ (is (= 0 (:consecutive-failures b)))])))))

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
       (is (= "open" (:state (SUT/breaker config destination)))))
     (testing "an open breaker answers at once, without calling"
       (let [res (SUT/guard config policy destination outcome-of (call :up))]
         (is (= :circuit-breaker/open (error/kind res)))
         (is (= 3 @calls)))))))
