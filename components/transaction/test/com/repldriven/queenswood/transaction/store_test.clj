(ns ^:eftest/synchronized com.repldriven.queenswood.transaction.store-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.transaction.store :as store]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(defn- transaction
  [transaction-id bank-id idempotency-key]
  {:transaction-id transaction-id
   :bank-id bank-id
   :idempotency-key idempotency-key
   :status :transaction-status-posted
   :transaction-type :transaction-type-internal-transfer
   :currency "GBP"
   :created-at (utility/now)
   :updated-at (utility/now)})

(deftest transaction-idempotency-read-back-test
  (with-test-system
   [sys "classpath:transaction/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         key "idem-txn-0000000000000001"]
     (testing "first transaction saves"
       (nom-test> [_ (store/save-transaction
                      config
                      (transaction "txn.t1" "bnk.test" key))]))
     (testing
       "a second transaction reusing [bank-id, transaction-type,
              idempotency-key] violates the compound unique index"
       (is (store/uniqueness-violation?
            (store/save-transaction config
                                    (transaction "txn.t2" "bnk.test" key)))))
     (testing "the same key and type from another bank saves"
       (nom-test> [_ (store/save-transaction
                      config
                      (transaction "txn.t3" "bnk.other" key))]))
     (testing "read-back is scoped to the bank that wrote the key"
       (nom-test> [found (store/find-transaction-by-idempotency-key
                          config
                          "bnk.test"
                          :transaction-type-internal-transfer
                          key)
                   _ (is (= "txn.t1" (:transaction-id found)))
                   _ (is (= "bnk.test" (:bank-id found)))
                   _ (is (= key (:idempotency-key found)))
                   other (store/find-transaction-by-idempotency-key
                          config
                          "bnk.other"
                          :transaction-type-internal-transfer
                          key)
                   _ (is (= "txn.t3" (:transaction-id other)))
                   _ (is (= "bnk.other" (:bank-id other)))])))))
