(ns ^:eftest/synchronized com.repldriven.queenswood.build.build-test
  (:require
    [com.repldriven.queenswood.build.build :as SUT]

    [org.corfield.build :as bb]

    [clojure.test :as test :refer [deftest is testing]]
    [clojure.tools.build.api :as b]))

(deftest uber-version-test
  (testing "Version string with default major-minor"
    (with-redefs [b/git-count-revs (constantly "42")
                  bb/clean identity
                  bb/uber identity]
      (let [result (SUT/uber {})] (is (= "0.0.42" (:version result))))))
  (testing "Version string with custom major-minor"
    (with-redefs [b/git-count-revs (constantly "42")
                  bb/clean identity
                  bb/uber identity]
      (let [result (SUT/uber {:major-minor-version "1.2"})]
        (is (= "1.2.42" (:version result))))))
  (testing "Version string with snapshot flag"
    (with-redefs [b/git-count-revs (constantly "42")
                  bb/clean identity
                  bb/uber identity]
      (let [result (SUT/uber {:snapshot true})]
        (is (= "0.0.999-SNAPSHOT" (:version result))))))
  (testing "Snapshot overrides git count"
    (with-redefs [b/git-count-revs (constantly "42")
                  bb/clean identity
                  bb/uber identity]
      (let [result (SUT/uber {:major-minor-version "2.0" :snapshot true})]
        (is (= "2.0.999-SNAPSHOT" (:version result)))))))

(deftest uber-options-test
  (testing "Transitive flag is set"
    (with-redefs [b/git-count-revs (constantly "42")
                  bb/clean identity
                  bb/uber identity]
      (let [result (SUT/uber {})] (is (true? (:transitive result))))))
  (testing "Conflict handlers are configured"
    (with-redefs [b/git-count-revs (constantly "42")
                  bb/clean identity
                  bb/uber identity]
      (let [result (SUT/uber {})
            handlers (:conflict-handlers result)]
        (is (map? handlers))
        (is (= :data-readers (get handlers "^data_readers.clj[cs]?$")))
        (is (= :append (get handlers "^META-INF/services/.*")))
        (is (= :ignore (:default handlers))))))
  (testing "Original options are preserved"
    (with-redefs [b/git-count-revs (constantly "42")
                  bb/clean identity
                  bb/uber identity]
      (let [opts {:lib 'my.lib :main 'my.main}
            result (SUT/uber opts)]
        (is (= 'my.lib (:lib result)))
        (is (= 'my.main (:main result))))))
  (testing "Build pipeline calls clean then uber"
    (let [calls (atom [])
          mock-clean (fn [opts] (swap! calls conj :clean) opts)
          mock-uber (fn [opts] (swap! calls conj :uber) opts)]
      (with-redefs [b/git-count-revs (constantly "42")
                    bb/clean mock-clean
                    bb/uber mock-uber]
        (SUT/uber {})
        (is (= [:clean :uber] @calls))))))
