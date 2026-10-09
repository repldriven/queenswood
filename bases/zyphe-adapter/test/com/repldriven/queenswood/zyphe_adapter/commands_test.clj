(ns com.repldriven.queenswood.zyphe-adapter.commands-test
  (:require
    [com.repldriven.queenswood.zyphe-adapter.commands :as SUT]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.processor.interface :as processor]

    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))

(def ^:private schema
  (delay (avro/json->schema
          (slurp (io/resource "schemas/idv/submit-idv-check.avsc.json")))))

(defn- message
  [verification-id]
  {:command "submit-idv-check"
   :payload (avro/serialize @schema
                            {:bank-id "bnk.1"
                             :verification-id verification-id
                             :party-id "pty.1"
                             :verifications []
                             :screenings []})})

(deftest performer-key-test
  (let [p (SUT/->ZypheCommandProcessor {:schemas {"submit-idv-check" @schema}})
        key-of (processor/performer-key-fn p)]
    (testing "a command is handed to a performer by its verification"
      (is (= "idv.1" (key-of (message "idv.1")))))
    (testing "a command the adapter does not handle keeps its send key"
      (is (nil? (key-of {:command "unknown" :payload (byte-array 0)}))))))
