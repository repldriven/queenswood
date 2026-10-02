(ns com.repldriven.queenswood.intent-queue.interface-test
  (:require
    [com.repldriven.queenswood.intent-queue.interface :as SUT]

    [clojure.test :refer [deftest is testing]]))

(defn- intent
  [intent-id status kind & subjects]
  {:intent-id intent-id :status status :kind kind :subjects (vec subjects)})

(defn- closes-settle-first?
  [intent]
  (= "close" (:kind intent)))

(defn- drain
  [intents outcomes]
  (let [ran (atom [])]
    (SUT/drain intents
               1000
               {:run (fn [i]
                       (swap! ran conj (:intent-id i))
                       (assoc i :status (get outcomes (:intent-id i) "sent")))
                :settles-first? closes-settle-first?})
    @ran))

(deftest drain-test
  (testing "intents for different subjects all run, oldest first"
    (is (= ["i1" "i2"]
           (drain [(intent "i2" "pending" "payment" "b")
                   (intent "i1" "pending" "payment" "a")]
                  {}))))
  (testing "a later intent runs once an earlier one for its subject is sent"
    (is (= ["i1" "i2"]
           (drain [(intent "i1" "pending" "payment" "a")
                   (intent "i2" "pending" "payment" "a")]
                  {}))))
  (testing "an earlier intent left pending holds its subject's later ones"
    (is (= ["i1" "i3"]
           (drain [(intent "i1" "pending" "payment" "a")
                   (intent "i2" "pending" "payment" "a")
                   (intent "i3" "pending" "payment" "b")]
                  {"i1" "pending"}))))
  (testing "one not yet due holds its subjects without running"
    (is (= ["i3"]
           (drain [(assoc (intent "i1" "pending" "payment" "a")
                          :next-attempt-at
                          5000) (intent "i2" "pending" "payment" "a")
                   (intent "i3" "pending" "payment" "b")]
                  {}))))
  (testing "a transfer holds both its accounts"
    (is (= ["i1"]
           (drain [(intent "i1" "pending" "transfer" "a" "b")
                   (intent "i2" "pending" "payment" "b")]
                  {"i1" "pending"}))))
  (testing "a close waits for an earlier sent transfer to settle"
    (is (= []
           (drain [(intent "i1" "sent" "transfer" "a" "b")
                   (intent "i2" "pending" "close" "b")]
                  {}))))
  (testing "but a payment does not"
    (is (= ["i2"]
           (drain [(intent "i1" "sent" "payment" "a")
                   (intent "i2" "pending" "payment" "a")]
                  {}))))
  (testing "a close runs once what came before it has settled"
    (is (= ["i1" "i2"]
           (drain [(intent "i1" "pending" "transfer" "a" "b")
                   (intent "i2" "pending" "close" "b")]
                  {"i1" "settled"}))))
  (testing "an intent naming no subject waits for nothing"
    (is (= ["i1" "i2"]
           (drain [(intent "i1" "pending" "payment" "a")
                   (intent "i2" "pending" "return")]
                  {"i1" "pending"})))))
