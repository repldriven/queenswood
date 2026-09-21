(ns com.repldriven.queenswood.reward-api.interface-test
  "`->body` is what stands between a stored reward and a response body,
  so what it lets through is held to what `Reward` declares, and the
  wire projection to the spelling a route sends. The expected key set
  is read back out of the published registry rather than off the
  projection's own selection."
  (:require
    [com.repldriven.queenswood.reward-api.interface :as SUT]

    [clojure.test :refer [deftest is testing]]))

(defn- declared-keys
  [component]
  (into [] (comp (filter vector?) (map first)) (get SUT/registry component)))

(def ^:private stored
  "A reward as the query brick hands it back: every key `Reward`
  declares, and nothing else."
  {:reward-id "rwd.01kprbmgcj35ptc8npmybhh4t1"
   :bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :account-id "acc.01kprbmgcj35ptc8npmybhh4s8"
   :party-id "pty.01kprbmgcj35ptc8npmybhh4s9"
   :product-id "prd.01kprbmgcj35ptc8npmybhh4se"
   :version-id "prv.01kprbmgcj35ptc8npmybhh4sf"
   :kind :reward-kind-opening
   :amount 5000
   :currency "GBP"
   :status :reward-status-paid
   :transaction-id "txn.01kprbmgcj35ptc8npmybhh4sb"
   :run-id "run.01kprbmgcj35ptc8npmybhh4t2"
   :paid-at 1700000000000
   :created-at 1700000000000
   :updated-at 1700000000001})

(deftest body-test
  (testing "the body carries every declared key the row holds, and no other"
    (is (= (set (filter (partial contains? stored) (declared-keys "Reward")))
           (set (keys (SUT/->body stored))))))
  (testing "a key the row holds and the component does not declare is dropped"
    (is (not (contains? (SUT/->body (assoc stored :secret "x")) :secret))))
  (testing "the wire body spells the enums and the timestamps as a route does"
    (let [wire (SUT/->wire-body stored)]
      (is (= :paid (:status wire)))
      (is (= :opening (:kind wire)))
      (is (= "2023-11-14T22:13:20Z" (:created-at wire)))
      (is (= 5000 (:amount wire))))))
