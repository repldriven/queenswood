(ns com.repldriven.queenswood.fdb.transact-test
  "A storage failure and a domain rejection reach `api` by the same
  route — an anomaly whose kind becomes the RFC 9457 problem type. Until
  `transact` classified, a contended write surfaced under whatever
  category its caller passed, so a busy cluster read as though the
  request had been rejected on its merits.

  These pin the classification, and pin that a rejection travelling the
  same path is left alone."
  (:require
    [com.repldriven.queenswood.fdb.transact :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]])
  (:import
    (com.apple.foundationdb FDBException)
    (com.apple.foundationdb.record LoggableTimeoutException
                                   RecordCoreException
                                   RecordCoreRetriableTransactionException)
    (com.apple.foundationdb.record.provider.foundationdb
     FDBExceptions$FDBStoreTransactionConflictException)))

(defn- txn
  []
  (SUT/->Txn (fn [_] nil) nil nil))

(defn- failing
  "Runs `transact` over a body that throws `e`, under a caller-supplied
  category, and returns the resulting anomaly."
  [^Throwable e]
  (SUT/transact (txn)
                ;; nosemgrep: no-raw-throw
                (fn [_] (throw e))
                :user/save
                "Failed to save user"))

(deftest unclassified-exception-keeps-the-callers-category-test
  (testing
    "an exception the storage layer cannot name is left as the
           caller framed it, and gains no :operation"
    (let [anomaly (failing (RuntimeException. "something else"))]
      (is (= :user/save (error/kind anomaly)))
      (is (nil? (:operation (error/payload anomaly)))))))

(deftest timeout-is-classified-test
  (testing
    "an asyncToSync deadline becomes :fdb/timeout, and the
           caller's category survives as :operation"
    (let [anomaly (failing (LoggableTimeoutException. (RuntimeException.
                                                       "deadline")
                                                      (into-array Object [])))]
      (is (= :fdb/timeout (error/kind anomaly)))
      (is (= :user/save (:operation (error/payload anomaly)))))))

(deftest contention-is-classified-test
  (testing "a retriable transaction failure becomes :fdb/contention"
    (let [anomaly (failing (RecordCoreRetriableTransactionException.
                            "not_committed"))]
      (is (= :fdb/contention (error/kind anomaly)))
      (is (= :user/save (:operation (error/payload anomaly)))))))

(deftest contention-matches-subclasses-test
  (testing
    "matching the Record Layer's own parent type means a write
           conflict classifies without being enumerated"
    (let [conflict (FDBExceptions$FDBStoreTransactionConflictException.
                    (FDBException. "not_committed" 1020))
          anomaly (failing conflict)]
      (is (instance? RecordCoreRetriableTransactionException conflict)
          "guards the assumption the classifier rests on")
      (is (= :fdb/contention (error/kind anomaly))))))

(deftest classification-walks-to-the-root-cause-test
  (testing
    "FDB wraps what it rethrows, so the cause chain is walked
           rather than only the outermost throwable"
    (let [nested (RuntimeException.
                  "outer"
                  (RecordCoreException. "middle" (into-array Object [])))
          _ (.initCause ^Throwable (.getCause ^Throwable nested)
                        (RecordCoreRetriableTransactionException. "root"))
          anomaly (failing nested)]
      (is (= :fdb/contention (error/kind anomaly))))))

(deftest the-exception-is-still-carried-test
  (testing
    "reclassifying rebuilds the anomaly, so the payload that
           makes a 5xx diagnosable has to survive it"
    (let [anomaly (failing (RecordCoreRetriableTransactionException. "x"))
          {:keys [message exception stack-trace]} (error/payload anomaly)]
      (is (= "Failed to save user" message))
      (is (some? exception))
      (is (some? stack-trace)))))

(deftest a-rejection-is-not-reclassified-test
  (testing
    "a domain rejection returned by the body carries no
           exception, and must reach the caller as raised"
    (let [rejection (error/reject :party/invalid-status {:message "no"})
          result (SUT/transact (txn)
                               (fn [_] rejection)
                               :user/save
                               "Failed to save user")]
      (is (= rejection result))
      (is (error/rejection? result))
      (is (= :party/invalid-status (error/kind result))))))
