(ns com.repldriven.queenswood.cash-account.store-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.cash-account.store :as store]

    [com.repldriven.queenswood.cash-account-query.interface :as q]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private test-bank-id "bnk_idem_test")

(defn- account
  [account-id idempotency-key]
  {:bank-id test-bank-id
   :account-id account-id
   :party-id "pty.test"
   :product-id "prd.test"
   :version-id "v1"
   :version-from-on 20089
   :created-by {:kind :actor-kind-operator :principal-id "test"}
   :account-type :account-type-personal
   :product-type :account-product-type-sub-ledger-current
   :name "Idempotency Test Account"
   :currency "GBP"
   :status :cash-account-status-opening
   :created-at (utility/now)
   :updated-at (utility/now)
   :idempotency-key idempotency-key})

(defn- changelog
  [account-id]
  {:account-id account-id
   :status-after :cash-account-status-opening
   :change-kind :cash-account-change-kind-open})

(deftest idempotency-key-unique-index-and-read-back-test
  (with-test-system
   [sys "classpath:cash-account/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         key "idem-key-0000000000000001"]
     (testing "first open with an idempotency-key is saved"
       (nom-test> [_ (store/save-account config
                                         (account "acc.1" key)
                                         (changelog "acc.1"))]))
     (testing
       "a second, different account reusing the same
               [bank-id, idempotency-key] hits the unique index"
       (let [result (store/save-account config
                                        (account "acc.2" key)
                                        (changelog "acc.2"))]
         (is (store/uniqueness-violation? result)
             "duplicate idempotency-key for the same bank must violate")))
     (testing "read-back returns the original account, not the duplicate"
       (nom-test> [found
                   (q/find-account-by-idempotency-key config test-bank-id key)
                   _ (is (= "acc.1" (:account-id found)))
                   _ (is (= key (:idempotency-key found)))]))
     (testing "a different idempotency-key for the same bank is allowed"
       (nom-test> [_ (store/save-account config
                                         (account "acc.3"
                                                  "idem-key-0000000000000002")
                                         (changelog "acc.3"))])))))
