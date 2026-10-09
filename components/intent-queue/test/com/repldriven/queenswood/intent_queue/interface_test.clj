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
    (SUT/drain
     intents
     1000
     {:run (fn [i]
             (swap! ran conj (:intent-id i))
             (assoc i
                    :status
                    (get outcomes (:intent-id i) :outbound-intent-status-sent)))
      :settles-first? closes-settle-first?})
    @ran))

(deftest drain-test
  (testing "intents for different subjects all run, oldest first"
    (is (= ["i1" "i2"]
           (drain [(intent "i2" :outbound-intent-status-pending "payment" "b")
                   (intent "i1" :outbound-intent-status-pending "payment" "a")]
                  {}))))
  (testing "a later intent runs once an earlier one for its subject is sent"
    (is (= ["i1" "i2"]
           (drain [(intent "i1" :outbound-intent-status-pending "payment" "a")
                   (intent "i2" :outbound-intent-status-pending "payment" "a")]
                  {}))))
  (testing "an earlier intent left pending holds its subject's later ones"
    (is (= ["i1" "i3"]
           (drain [(intent "i1" :outbound-intent-status-pending "payment" "a")
                   (intent "i2" :outbound-intent-status-pending "payment" "a")
                   (intent "i3" :outbound-intent-status-pending "payment" "b")]
                  {"i1" :outbound-intent-status-pending}))))
  (testing "one not yet due holds its subjects without running"
    (is (= ["i3"]
           (drain [(assoc (intent "i1" :outbound-intent-status-pending
                                  "payment" "a")
                          :next-attempt-at
                          5000)
                   (intent "i2" :outbound-intent-status-pending "payment" "a")
                   (intent "i3" :outbound-intent-status-pending "payment" "b")]
                  {}))))
  (testing "a transfer holds both its accounts"
    (is (= ["i1"]
           (drain
            [(intent "i1" :outbound-intent-status-pending "transfer" "a" "b")
             (intent "i2" :outbound-intent-status-pending "payment" "b")]
            {"i1" :outbound-intent-status-pending}))))
  (testing "a close waits for an earlier sent transfer to settle"
    (is (= []
           (drain [(intent "i1" :outbound-intent-status-sent "transfer" "a" "b")
                   (intent "i2" :outbound-intent-status-pending "close" "b")]
                  {}))))
  (testing "but a payment does not"
    (is (= ["i2"]
           (drain [(intent "i1" :outbound-intent-status-sent "payment" "a")
                   (intent "i2" :outbound-intent-status-pending "payment" "a")]
                  {}))))
  (testing "a close runs once what came before it has settled"
    (is (= ["i1" "i2"]
           (drain
            [(intent "i1" :outbound-intent-status-pending "transfer" "a" "b")
             (intent "i2" :outbound-intent-status-pending "close" "b")]
            {"i1" :outbound-intent-status-settled}))))
  (testing "an intent naming no subject waits for nothing"
    (is (= ["i1" "i2"]
           (drain [(intent "i1" :outbound-intent-status-pending "payment" "a")
                   (intent "i2" :outbound-intent-status-pending "return")]
                  {"i1" :outbound-intent-status-pending})))))

(defn- runnable-ids
  [intents]
  (mapv :intent-id
        (SUT/runnable intents 1000 {:settles-first? closes-settle-first?})))

(deftest runnable-test
  (testing "intents for different subjects run together, oldest first"
    (is (= ["i1" "i2"]
           (runnable-ids
            [(intent "i2" :outbound-intent-status-pending "payment" "b")
             (intent "i1" :outbound-intent-status-pending "payment" "a")]))))
  (testing "a later intent for a running one's subject waits for a pass"
    (is (= ["i1" "i3"]
           (runnable-ids
            [(intent "i1" :outbound-intent-status-pending "payment" "a")
             (intent "i2" :outbound-intent-status-pending "payment" "a")
             (intent "i3" :outbound-intent-status-pending "payment" "b")]))))
  (testing "a transfer holds both its accounts"
    (is (= ["i1" "i4"]
           (runnable-ids
            [(intent "i1" :outbound-intent-status-pending "transfer" "a" "b")
             (intent "i2" :outbound-intent-status-pending "payment" "b")
             (intent "i3" :outbound-intent-status-pending "transfer" "a" "c")
             (intent "i4" :outbound-intent-status-pending "payment" "d")]))))
  (testing "one not yet due holds its subjects"
    (is (= ["i3"]
           (runnable-ids
            [(assoc (intent "i1" :outbound-intent-status-pending "payment" "a")
                    :next-attempt-at
                    5000)
             (intent "i2" :outbound-intent-status-pending "payment" "a")
             (intent "i3" :outbound-intent-status-pending "payment" "b")]))))
  (testing "a close waits for an earlier sent transfer to settle"
    (is (= []
           (runnable-ids
            [(intent "i1" :outbound-intent-status-sent "transfer" "a" "b")
             (intent "i2" :outbound-intent-status-pending "close" "b")]))))
  (testing "but a payment does not"
    (is (= ["i2"]
           (runnable-ids
            [(intent "i1" :outbound-intent-status-sent "transfer" "a" "b")
             (intent "i2" :outbound-intent-status-pending "payment" "b")]))))
  (testing "an intent naming no subject waits for nothing"
    (is (= ["i1" "i2"]
           (runnable-ids
            [(intent "i1" :outbound-intent-status-pending "payment")
             (intent "i2" :outbound-intent-status-pending "payment")])))))
