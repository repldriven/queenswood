(ns com.repldriven.queenswood.scheme-simulator.interface-test
  (:require
    [com.repldriven.queenswood.scheme-simulator.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private payment
  {:amount 25.0 :currency "GBP" :reference "Towel" :debtor-name "Ford"})

(deftest send-inbound-test
  (testing "nothing is sent without a scheme"
    (is (nil? (SUT/send-inbound nil (assoc payment :bban "04009912345678")))))
  (let [scheme (atom nil)]
    (with-test-system
     [sys "classpath:scheme-simulator/application-test.yml"]
     (reset! scheme (system/instance sys [:scheme-simulator :scheme]))
     (testing "nothing is sent where no member holds the sort code"
       (is (nil? (SUT/send-inbound @scheme
                                   (assoc payment :bban "04001012345678")))))
     (testing "a member that cannot be reached is a failure"
       (is (error/anomaly? (SUT/send-inbound
                            @scheme
                            (assoc payment :bban "04009912345678"))))))
    (testing "a member leaves the scheme when it stops"
      (is (nil? (SUT/send-inbound @scheme
                                  (assoc payment :bban "04009912345678")))))))
