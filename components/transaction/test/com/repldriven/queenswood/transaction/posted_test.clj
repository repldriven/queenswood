(ns com.repldriven.queenswood.transaction.posted-test
  (:require
    [com.repldriven.queenswood.transaction.test-system]

    [com.repldriven.queenswood.transaction.interface :as SUT]

    [com.repldriven.queenswood.bank-activity.interface :as bank-activity]
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(defn- leg
  [account-id side amount & {:as extra}]
  (merge {:account-id account-id
          :balance-type :balance-type-default
          :balance-status :balance-status-posted
          :side side
          :amount amount}
         extra))

(deftest posting-records-its-bank-activity-test
  (with-test-system
   [sys "classpath:transaction/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         schemas (system/instance sys [:avro :serde])
         entries (atom [])
         recorded (SUT/record-transaction
                   config
                   {:bank-id "bnk.1"
                    :idempotency-key "k-1"
                    :transaction-type :transaction-type-inbound-transfer
                    :currency "GBP"
                    :scheme-account-id "acc.1"
                    :legs [(leg "gl.1100" :leg-side-debit 500)
                           (leg "acc.1" :leg-side-credit 500)]})]
     (nom-test> [_ recorded
                 _ (fdb/process-changelog
                    (:record-db config)
                    "posted-test"
                    (bank-activity/log-name "bnk.1")
                    (fn [_ bytes]
                      (swap! entries conj (schema/pb->ChangelogEvent bytes))
                      nil)
                    {:deduplicate? false
                     :keyspace-prefix (system/instance sys
                                                       [:fdb
                                                        :keyspace-prefix])})])
     (let [[entry] @entries
           data (avro/deserialize-same (get schemas "transaction-posted")
                                       (:payload entry))]
       (testing "one entry, keyed and ordered by the bank"
         (is (= 1 (count @entries)))
         (is (= "transaction-posted" (:event-name entry)))
         (is (= "bnk.1" (:ordering-key entry))))
       (testing "it carries the legs"
         (is (= (:transaction-id recorded) (:transaction-id data)))
         (is (= :transaction-type-inbound-transfer (:transaction-type data)))
         (is (= [["gl.1100" 500] ["acc.1" 500]]
                (mapv (fn [l] [(:account-id l) (:amount l)]) (:legs data)))))
       (testing "and the account the scheme moved the money through"
         (is (= "acc.1" (:scheme-account-id data))))))))
