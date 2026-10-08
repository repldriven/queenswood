(ns com.repldriven.queenswood.test-scenarios.corpus-test
  "Checks over the scenario corpus and the verb table that need no
  running system."
  (:require
    [com.repldriven.queenswood.test-scenarios.interface :as SUT]

    [com.repldriven.queenswood.test-model.interface :as model]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(deftest every-scenario-validates-test
  (let [files (SUT/scenario-files)]
    (is (seq files))
    (doseq [{:keys [relative]} files]
      (testing relative
        (let [loaded (SUT/from-resource (SUT/scenario-resource relative))]
          (is (not (error/anomaly? loaded)) (pr-str loaded)))))))

(deftest every-verb-has-a-kind-and-a-method-test
  (is (= (set (keys SUT/verbs)) (SUT/verb-methods))))

(deftest model-and-fixture-verbs-are-the-model-test
  (is (= (set (keys model/model))
         (set (keep (fn [[verb {:keys [kind]}]]
                      (when (contains? #{:model :fixture} kind) verb))
                    SUT/verbs)))))

(def ^:private bank [{:command :create-bank :args []}])

(deftest a-compared-scenario-names-no-reality-verb-test
  (testing "a compared scenario naming a reality verb is refused"
    (let [parsed (SUT/parse "inline.edn"
                            {:name "compared"
                             :given bank
                             :when [{:command :close-ledger-account
                                     :args [:bank-0
                                            :ledger-account-code-suspense]}]})]
      (is (= :test-scenarios/scenario (error/kind parsed)))
      (is (= [:close-ledger-account] (:verbs (error/payload parsed))))
      (is (= "inline.edn" (:resource (error/payload parsed))))))
  (testing "the same scenario marked reality-only loads"
    (is (not (error/anomaly?
              (SUT/parse "inline.edn"
                         {:name "reality"
                          :model :reality
                          :given bank
                          :when [{:command :close-ledger-account
                                  :args [:bank-0
                                         :ledger-account-code-suspense]}]}))))))

(deftest sections-test
  (testing "a :given step never asserts"
    (is (error/anomaly? (SUT/parse "inline.edn"
                                   {:name "assert in given"
                                    :given [{:command :assert-no-anomaly
                                             :args []}]
                                    :when bank}))))
  (testing "a :then step never writes"
    (is (error/anomaly? (SUT/parse
                         "inline.edn"
                         {:name "write in then" :when bank :then bank})))))

(deftest arguments-test
  (testing "a verb's arguments are closed"
    (is (error/anomaly? (SUT/parse "inline.edn"
                                   {:name "an extra argument"
                                    :when [{:command :create-bank
                                            :args [:bank-0]}]}))))
  (testing "a model id names its kind"
    (is (error/anomaly? (SUT/parse "inline.edn"
                                   {:name "a bank where an account belongs"
                                    :when (into bank
                                                [{:command :inbound-transfer
                                                  :args [:bank-0 100]}])}))))
  (testing "an unknown verb is refused"
    (is (error/anomaly? (SUT/parse "inline.edn"
                                   {:name "unknown"
                                    :when [{:command :no-such-verb
                                            :args []}]})))))
