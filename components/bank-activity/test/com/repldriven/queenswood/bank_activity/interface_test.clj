(ns com.repldriven.queenswood.bank-activity.interface-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.bank-activity.interface :as SUT]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(defn- closing
  [bank-id account-id]
  {:bank-id bank-id
   :event-name "cash-account-close-requested"
   :data {:account-id account-id :provider-account-id (str "p." account-id)}
   :causation-id account-id
   :dedup-key account-id})

(defn- wait-for
  [pred deadline-ms]
  (let [deadline (+ (utility/now) deadline-ms)]
    (loop []
      (cond
       (pred)
       true

       (>= (utility/now) deadline)
       false

       :else
       (do (Thread/sleep 25) (recur))))))

(defn- capture
  [seen]
  (reify
   clojure.lang.IFn
     (invoke [_ _ctx changelog-bytes]
       (swap! seen conj (schema/pb->ChangelogEvent changelog-bytes)))))

(deftest log-name-test
  (testing "a bank's log is stable and one of the shards"
    (let [names
          (into #{} (map SUT/log-name) (map (partial str "bnk.") (range 64)))]
      (is (= (SUT/log-name "bnk.1") (SUT/log-name "bnk.1")))
      (is (= SUT/shard-count (count names))))))

(deftest record-test
  (let [seen (atom [])]
    (with-test-system
     [sys
      ["classpath:bank-activity/application-test.yml"
       #(assoc-in % [:system/defs :activity :handler] (capture seen))]]
     (let [config {:record-db (system/instance sys [:fdb :record-db])
                   :record-store (system/instance sys [:fdb :store])}
           banks (mapv (fn [n] (str "bnk." (utility/uuidv7) "." n)) (range 8))
           accounts (mapv (fn [n] (str "acc." n)) (range 3))]
       (testing
         "each bank's entries are published in commit order, keyed
                 by bank"
         (run! (fn [account-id]
                 (run! (fn [bank-id]
                         (fdb/transact
                          config
                          (fn [txn]
                            (SUT/record txn (closing bank-id account-id)))))
                       banks))
               accounts)
         (is (wait-for (fn [] (= (* 3 8) (count @seen))) 10000))
         (run! (fn [bank-id]
                 (is (= (map (fn [a] (str "cash-account-close-requested:" a))
                             accounts)
                        (keep (fn [{:keys [ordering-key dedup-key]}]
                                (when (= bank-id ordering-key) dedup-key))
                              @seen))))
               banks))
       (testing "an event with no activity schema is refused"
         (let [result (fdb/transact config
                                    (fn [txn]
                                      (SUT/record txn
                                                  (assoc (closing "bnk.x"
                                                                  "acc.x")
                                                         :event-name
                                                         "account-vanished"))))]
           (is (= :bank-activity/unknown-event (error/kind result)))))
       (testing "a payload missing a field writes nothing"
         (is (error/anomaly? (fdb/transact config
                                           (fn [txn]
                                             (SUT/record
                                              txn
                                              (update
                                               (closing "bnk.y" "acc.y")
                                               :data
                                               dissoc
                                               :provider-account-id)))))))))))
