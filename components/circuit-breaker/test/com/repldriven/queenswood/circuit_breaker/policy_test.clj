(ns com.repldriven.queenswood.circuit-breaker.policy-test
  "The retry policy an operation is given, its backoff and when it gives
  up."
  (:require
    [com.repldriven.queenswood.circuit-breaker.policy :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private delivery-policy
  {:default {:initial-backoff-ms 1000
             :backoff-growth 2
             :max-backoff-ms 5000
             :max-attempts 5
             :max-age-ms 60000}
   :operations {:reissue-address {:max-age-ms 600000}}})

(deftest retry-policy-test
  (testing "an operation's entry is merged over the default"
    (is (= 600000
           (:max-age-ms (SUT/retry-policy delivery-policy "reissue-address"))))
    (is (= 5
           (:max-attempts (SUT/retry-policy delivery-policy
                                            "reissue-address")))))
  (testing "an operation with no entry, or none, takes the default"
    (is (= (:default delivery-policy)
           (SUT/retry-policy delivery-policy "payment")
           (SUT/retry-policy delivery-policy nil)))))

(deftest backoff-test
  (let [p (:default delivery-policy)]
    (testing "the backoff grows from the initial and stops at its maximum"
      (is (= [1000 2000 4000 5000 5000]
             (mapv (fn [n] (SUT/backoff-ms p n)) [1 2 3 4 5]))))))

(deftest give-up-test
  (let [p (:default delivery-policy)]
    (testing "an item gives up at its maximum attempts"
      (is (not (SUT/give-up? p 4 0)))
      (is (SUT/give-up? p 5 0)))
    (testing "or past its maximum age, whatever its attempts"
      (is (SUT/give-up? p 1 60001))
      (is (not (SUT/give-up? p 1 nil))))))
