(ns com.repldriven.queenswood.fdb.transact-span-test
  "A domain rule rejecting inside a transaction rolls it back by
  throwing, which marked `fdb-transaction` as an error for a rejection
  and a failure alike. These pin that the span records which it was,
  and is marked as an error for a failure alone."
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.fdb.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]
    [com.repldriven.mono.test-telemetry.interface :as test-telemetry]

    [clojure.test :refer [deftest is testing]])
  (:import
    (io.opentelemetry.api.common AttributeKey)
    (io.opentelemetry.api.trace StatusCode)))

(defn- outcome
  "The outcome recorded on the one `fdb-transaction` span whose category
  is `category`."
  [otel category]
  (let [attr (fn [span k]
               (.get (.getAttributes span) (AttributeKey/stringKey k)))
        span (->> (test-telemetry/finished-spans otel)
                  (filter (fn [span]
                            (and (= "fdb-transaction" (.getName span))
                                 (= (str category)
                                    (attr span "fdb.category")))))
                  first)]
    (when span
      {:outcome (attr span "fdb.outcome")
       :reason (attr span "fdb.reason")
       :error? (= StatusCode/ERROR (.getStatusCode (.getStatus span)))})))

(deftest span-records-the-outcome-test
  (with-test-system
   [sys "classpath:fdb/traced-test.yml"]
   (let [otel (system/instance sys [:telemetry :otel-sdk])
         config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :pet-store])}]
     (testing "a committed transaction is not an error"
       (SUT/transact config (fn [_] :done) :span-test/committed "committed")
       (is (= {:outcome "committed" :reason nil :error? false}
              (outcome otel :span-test/committed))))
     (testing "a rejection rolls back without marking the span"
       (let [result (SUT/transact config
                                  (fn [_]
                                    (error/reject :span-test/declined
                                                  {:message "declined"}))
                                  :span-test/rejected
                                  "rejected")]
         (is (error/rejection? result))
         (is (=
              {:outcome "rejected" :reason ":span-test/declined" :error? false}
              (outcome otel :span-test/rejected)))))
     (testing "a failure marks the span as an error"
       (let [result (SUT/transact
                     config
                     (fn [_] (error/fail :span-test/broken {:message "broken"}))
                     :span-test/failed
                     "failed")]
         (is (error/error? result))
         (is (= {:outcome "failed" :reason ":span-test/broken" :error? true}
                (outcome otel :span-test/failed))))))))
