(ns com.repldriven.queenswood.test-startup.interface-test
  (:require
    [com.repldriven.queenswood.test-startup.interface :as SUT]

    [com.repldriven.mono.test-system.interface :refer [nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(deftest service-project-starts-to-its-definitions-test
  (if-let [project (SUT/project)]
    (testing (str project " loads its entry base and resolves its config")
      (nom-test> [result (SUT/check project)
                  _ (is (pos? (:registered result)) (pr-str result))]))
    (testing "no service project's application.yml is on this classpath"
      (is (nil? (SUT/project))))))
