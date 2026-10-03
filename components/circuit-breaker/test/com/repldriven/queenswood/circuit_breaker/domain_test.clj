(ns com.repldriven.queenswood.circuit-breaker.domain-test
  "The breaker's states over a sequence of call outcomes."
  (:require
    [com.repldriven.queenswood.circuit-breaker.domain :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private policy
  {:failure-threshold 3
   :cool-down-ms 1000
   :max-cool-down-ms 3000
   :probe-lease-ms 500})

(defn- outcomes
  "The breaker after recording each outcome in turn, a millisecond apart."
  [breaker start & outcomes]
  (reduce (fn [b [i outcome]]
            (or (SUT/record b "adapter:x" outcome (+ start i) policy) b))
          breaker
          (map-indexed vector outcomes)))

(deftest closed-test
  (testing "no record is closed"
    (is (= [:closed nil] (SUT/allow nil 0 "r1" 500))))
  (testing "an answer to a destination with no record writes nothing"
    (is (nil? (SUT/record nil "adapter:x" :answered 0 policy))))
  (testing "failures below the threshold count, and an answer clears them"
    (let [b (outcomes nil 0 :failed :failed)]
      (is (= "closed" (:state b)))
      (is (= 2 (:consecutive-failures b)))
      (is (= 0 (:consecutive-failures (outcomes b 10 :answered)))))))

(deftest open-test
  (let [b (outcomes nil 0 :failed :failed :failed)]
    (testing "the threshold opens it for the cool-down"
      (is (= "open" (:state b)))
      (is (= 2 (:opened-at b)))
      (is (= 1002 (:retry-at b))))
    (testing "an open breaker holds calls until the cool-down ends"
      (is (= [:open nil] (SUT/allow b 1001 "r1" 500))))
    (testing "a failure while open changes nothing"
      (is (nil? (SUT/record b "adapter:x" :failed 1001 policy))))))

(deftest half-open-test
  (let [b (outcomes nil 0 :failed :failed :failed)
        [decision probing] (SUT/allow b 1002 "r1" 500)]
    (testing "past the cool-down one claimant probes"
      (is (= :probe decision))
      (is (= "half-open" (:state probing)))
      (is (= "r1" (:probe-claimed-by probing))))
    (testing "another claimant is held while the probe's lease lives"
      (is (= [:open nil] (SUT/allow probing 1100 "r2" 500))))
    (testing "a probe whose lease has passed is claimed again"
      (is (= :probe (first (SUT/allow probing 1502 "r2" 500)))))
    (testing "the probe's answer closes it"
      (is (= "closed"
             (:state (SUT/record probing "adapter:x" :answered 1200 policy)))))
    (testing "the probe's failure reopens it for twice the cool-down"
      (let [reopened (SUT/record probing "adapter:x" :failed 1200 policy)]
        (is (= "open" (:state reopened)))
        (is (= 2000 (:cool-down-ms reopened)))
        (is (= 3200 (:retry-at reopened)))
        (is (nil? (:probe-claimed-by reopened)))))
    (testing "the cool-down stops growing at its maximum"
      (let [reopened (SUT/record (assoc probing :cool-down-ms 2000)
                                 "adapter:x" :failed
                                 1200 policy)]
        (is (= 3000 (:cool-down-ms reopened)))))))
