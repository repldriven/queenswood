(ns com.repldriven.queenswood.test-startup.core-test
  (:require
    [com.repldriven.queenswood.test-startup.core :as SUT]

    [clojure.test :refer [deftest is testing]]))

(deftest entry-namespace-test
  (testing "the entry base is the project name without its service suffix"
    (is (= 'com.repldriven.queenswood.operational-processors.main
           (SUT/entry-namespace "operational-processors-service")))
    (is (= 'com.repldriven.queenswood.api.main
           (SUT/entry-namespace "api-service")))))

(deftest unregistered-test
  (let [defs {:system/defs {:fdb {:db {:system/start identity :system/config {}}
                                  :container {:system/component-kind
                                              :fdb/container
                                              :image-name "x"}}
                            :env {:port 8080}}}]
    (testing "a component still carrying its kind was never registered"
      (is (= [[:fdb :container :fdb/container]] (SUT/unregistered defs))))
    (testing "only components with a start fn count as registered"
      (is (= 1 (SUT/registered-count defs))))))
