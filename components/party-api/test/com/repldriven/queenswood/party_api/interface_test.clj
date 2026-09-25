(ns com.repldriven.queenswood.party-api.interface-test
  "`->body` is what stands between a stored party and a response body,
  so what it lets through is held to what `Party` declares, and
  `->wire-body` to the spelling a route sends. The expected key set is
  read back out of the published registry rather than off the
  projection's own selection."
  (:require
    [com.repldriven.queenswood.party-api.interface :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private declared-keys
  (into [] (comp (filter vector?) (map first)) (get SUT/registry "Party")))

(def ^:private stored
  "A party as the query brick hands it back: every key `Party` declares,
  and the idempotency key the record also stores and the API never
  publishes."
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :party-id "pty.01kprbmgcj35ptc8npmybhh4s9"
   :type :party-type-person
   :display-name "Arthur Dent"
   :status :party-status-merged
   :merged-into-party-id "pty.01kprbmgcj35ptc8npmybhh4sa"
   :created-at 1700000000000
   :updated-at 1700000000001
   :idempotency-key "5b2f0f6e-create"})

(deftest declared-keys-cover-the-fixture-test
  (testing "the fixture carries every key Party declares"
    (is (= (set declared-keys)
           (set (filter (set declared-keys) (keys stored)))))))

(deftest ->body-test
  (let [body (SUT/->body stored)]
    (testing "every declared key survives, value and all"
      (is (= (select-keys stored declared-keys) body)))
    (testing "the stored idempotency key does not reach a body"
      (is (not (contains? body :idempotency-key)))))
  (testing "an optional key the record lacks stays absent"
    (is (not (contains? (SUT/->body (dissoc stored :merged-into-party-id))
                        :merged-into-party-id)))))

(deftest ->wire-body-test
  (let [body (SUT/->wire-body stored)]
    (testing "the enums reach the wire as the strings Party admits"
      (is (= "person" (name (:type body))))
      (is (= "merged" (name (:status body)))))
    (testing "and the timestamps as ISO-8601"
      (is (= "2023-11-14T22:13:20Z" (:created-at body)))
      (is (= "2023-11-14T22:13:20.001Z" (:updated-at body))))
    (testing "the idempotency key still does not reach a body"
      (is (not (contains? body :idempotency-key))))))

(deftest party-status-enum-schema-test
  (let [encode (:encode/api (second (SUT/party-status-enum-schema)))]
    (testing "each status encodes to its wire string"
      (is (= :active (encode :party-status-active)))
      (is (= :rejected (encode :party-status-rejected))))))
