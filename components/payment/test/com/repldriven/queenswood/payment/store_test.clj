(ns ^:eftest/synchronized com.repldriven.queenswood.payment.store-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.payment.store :as store]

    [com.repldriven.queenswood.payment-query.interface :as q]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(defn- internal-payment
  [payment-id bank-id idempotency-key]
  {:payment-id payment-id
   :idempotency-key idempotency-key
   :debtor-account-id "acc.debtor"
   :creditor-account-id "acc.creditor"
   :currency "GBP"
   :amount 1500
   :transaction-id "txn.internal"
   :created-at (utility/now)
   :updated-at (utility/now)
   :bank-id bank-id
   :business-day 20260101})

(defn- outbound-payment
  [payment-id bank-id idempotency-key]
  {:payment-id payment-id
   :idempotency-key idempotency-key
   :scheme "fps"
   :debtor-account-id "acc.debtor"
   :creditor-bban "12345678901234"
   :creditor-name "Acme Ltd"
   :currency "GBP"
   :amount 2500
   :payment-status :outbound-payment-status-pending
   :transaction-id "txn.outbound"
   :created-at (utility/now)
   :bank-id bank-id
   :business-day 20260101})

(deftest internal-payment-idempotency-read-back-test
  (with-test-system
   [sys "classpath:payment/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         key "idem-internal-000000000001"]
     (testing "first internal payment saves"
       (nom-test> [_ (store/save-internal-payment
                      config
                      (internal-payment "pmt.i1" "bnk.test" key))]))
     (testing "a second payment reusing the key in the same bank violates"
       (is (store/uniqueness-violation?
            (store/save-internal-payment
             config
             (internal-payment "pmt.i2" "bnk.test" key)))))
     (testing "the same key from another bank saves"
       (nom-test> [_ (store/save-internal-payment
                      config
                      (internal-payment "pmt.i3" "bnk.other" key))]))
     (testing "read-back is scoped to the bank that wrote the key"
       (nom-test> [found (q/find-internal-payment-by-idempotency-key config
                                                                     "bnk.test"
                                                                     key)
                   _ (is (= "pmt.i1" (:payment-id found)))
                   _ (is (= "bnk.test" (:bank-id found)))
                   _ (is (= key (:idempotency-key found)))
                   other (q/find-internal-payment-by-idempotency-key config
                                                                     "bnk.other"
                                                                     key)
                   _ (is (= "pmt.i3" (:payment-id other)))
                   _ (is (= "bnk.other" (:bank-id other)))])))))

(deftest outbound-payment-idempotency-read-back-test
  (with-test-system
   [sys "classpath:payment/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         key "idem-outbound-000000000001"]
     (testing "first outbound payment saves"
       (nom-test> [_ (store/save-outbound-payment
                      config
                      (outbound-payment "pmt.o1" "bnk.test" key))]))
     (testing "a second payment reusing the key in the same bank violates"
       (is (store/uniqueness-violation?
            (store/save-outbound-payment
             config
             (outbound-payment "pmt.o2" "bnk.test" key)))))
     (testing "the same key from another bank saves"
       (nom-test> [_ (store/save-outbound-payment
                      config
                      (outbound-payment "pmt.o3" "bnk.other" key))]))
     (testing "read-back is scoped to the bank that wrote the key"
       (nom-test> [found (q/find-outbound-payment-by-idempotency-key config
                                                                     "bnk.test"
                                                                     key)
                   _ (is (= "pmt.o1" (:payment-id found)))
                   _ (is (= "bnk.test" (:bank-id found)))
                   _ (is (= key (:idempotency-key found)))
                   other (q/find-outbound-payment-by-idempotency-key config
                                                                     "bnk.other"
                                                                     key)
                   _ (is (= "pmt.o3" (:payment-id other)))
                   _ (is (= "bnk.other" (:bank-id other)))])))))
