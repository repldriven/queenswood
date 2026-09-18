(ns com.repldriven.queenswood.payment.store-test
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

(defn- inbound-payment
  [{:keys [payment-id] :as payment}]
  (merge {:scheme-transaction-id (str "stx." payment-id)
          :end-to-end-id "e2e.default"
          :scheme "fps"
          :creditor-account-id "acc.creditor"
          :currency "GBP"
          :amount 1000
          :created-at (utility/now)
          :updated-at (utility/now)
          :bank-id "bnk.test"
          :business-day 20260101
          :payment-status :inbound-payment-status-held}
         payment))

(defn- payment-ids
  [payments]
  (mapv :payment-id payments))

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

(deftest scoped-payment-read-test
  (with-test-system
   [sys "classpath:payment/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}]
     (testing "an internal, an outbound and an inbound payment save"
       (nom-test> [_ (store/save-internal-payment config
                                                  (internal-payment
                                                   "pmt.si1"
                                                   "bnk.test"
                                                   "idem-scoped-internal-0001"))
                   _ (store/save-outbound-payment config
                                                  (outbound-payment
                                                   "pmt.so1"
                                                   "bnk.test"
                                                   "idem-scoped-outbound-0001"))
                   _ (store/save-inbound-payment config
                                                 (inbound-payment {:payment-id
                                                                   "pmt.sn1"}))]))
     (testing "a scoped read answers the payment for its own bank"
       (nom-test> [internal
                   (q/find-internal-payment config "bnk.test" "pmt.si1")
                   _ (is (= "pmt.si1" (:payment-id internal)))
                   outbound
                   (q/find-outbound-payment config "bnk.test" "pmt.so1")
                   _ (is (= "pmt.so1" (:payment-id outbound)))
                   inbound (q/find-inbound-payment config "bnk.test" "pmt.sn1")
                   _ (is (= "pmt.sn1" (:payment-id inbound)))]))
     (testing "a scoped read answers nil for another bank"
       (nom-test> [internal
                   (q/find-internal-payment config "bnk.other" "pmt.si1")
                   _ (is (nil? internal))
                   outbound
                   (q/find-outbound-payment config "bnk.other" "pmt.so1")
                   _ (is (nil? outbound))
                   inbound (q/find-inbound-payment config "bnk.other" "pmt.sn1")
                   _ (is (nil? inbound))]))
     (testing "a scoped read answers nil for a missing payment"
       (nom-test> [inbound
                   (q/find-inbound-payment config "bnk.test" "pmt.missing")
                   _ (is (nil? inbound))])))))

(deftest open-hold-match-test
  (with-test-system
   [sys "classpath:payment/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         e2e "e2e.shared"]
     (testing "holds on one end-to-end id save beside a settled one"
       (nom-test> [_ (store/save-inbound-payment config
                                                 (inbound-payment
                                                  {:payment-id "pmt.h1"
                                                   :end-to-end-id e2e
                                                   :creditor-account-id "acc.a"
                                                   :amount 1000
                                                   :created-at 3000}))
                   _ (store/save-inbound-payment config
                                                 (inbound-payment
                                                  {:payment-id "pmt.h2"
                                                   :end-to-end-id e2e
                                                   :creditor-account-id "acc.b"
                                                   :amount 1000
                                                   :created-at 2000}))
                   _ (store/save-inbound-payment config
                                                 (inbound-payment
                                                  {:payment-id "pmt.h3"
                                                   :end-to-end-id e2e
                                                   :creditor-account-id "acc.a"
                                                   :amount 2500
                                                   :created-at 1000}))
                   _ (store/save-inbound-payment
                      config
                      (inbound-payment {:payment-id "pmt.h4"
                                        :end-to-end-id e2e
                                        :creditor-account-id "acc.a"
                                        :amount 1000
                                        :created-at 500
                                        :payment-status
                                        :inbound-payment-status-settled}))
                   _ (store/save-inbound-payment config
                                                 (inbound-payment
                                                  {:payment-id "pmt.h5"
                                                   :end-to-end-id "e2e.other"
                                                   :creditor-account-id "acc.a"
                                                   :amount 1000
                                                   :created-at 100}))]))
     (testing "every open hold on the end-to-end id, oldest first"
       (nom-test> [holds (q/find-open-holds config e2e)
                   _ (is (= ["pmt.h3" "pmt.h2" "pmt.h1"] (payment-ids holds)))]))
     (testing "holds on one end-to-end id are told apart by creditor"
       (nom-test> [hold (q/find-open-hold config e2e "acc.b" nil)
                   _ (is (= "pmt.h2" (:payment-id hold)))
                   hold (q/find-open-hold config e2e "acc.a" nil)
                   _ (is (= "pmt.h3" (:payment-id hold)))]))
     (testing "holds for one creditor are told apart by amount"
       (nom-test> [hold (q/find-open-hold config e2e "acc.a" 1000)
                   _ (is (= "pmt.h1" (:payment-id hold)))
                   hold (q/find-open-hold config e2e "acc.a" 2500)
                   _ (is (= "pmt.h3" (:payment-id hold)))]))
     (testing "no hold matches another creditor or amount"
       (nom-test> [hold (q/find-open-hold config e2e "acc.c" nil)
                   _ (is (nil? hold))
                   hold (q/find-open-hold config e2e "acc.b" 2500)
                   _ (is (nil? hold))
                   holds (q/find-open-holds config "e2e.missing")
                   _ (is (= [] holds))])))))

(deftest payment-status-list-test
  (with-test-system
   [sys "classpath:payment/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         outbound
         (fn [payment-id bank-id status created-at]
           (assoc (outbound-payment payment-id bank-id (str "idem-" payment-id))
                  :payment-status status
                  :created-at created-at))
         inbound (fn [payment-id bank-id status created-at]
                   (inbound-payment {:payment-id payment-id
                                     :bank-id bank-id
                                     :payment-status status
                                     :created-at created-at}))]
     (testing "outbound payments save in several statuses and banks"
       (nom-test> [_ (store/save-outbound-payment
                      config
                      (outbound "pmt.p1" "bnk.test"
                                :outbound-payment-status-pending 3000))
                   _ (store/save-outbound-payment
                      config
                      (outbound "pmt.p2" "bnk.test"
                                :outbound-payment-status-pending 1000))
                   _ (store/save-outbound-payment
                      config
                      (outbound "pmt.p3" "bnk.test"
                                :outbound-payment-status-completed 2000))
                   _ (store/save-outbound-payment
                      config
                      (outbound "pmt.p4" "bnk.other"
                                :outbound-payment-status-pending 2000))]))
     (testing "outbound payments by status span banks, oldest first"
       (nom-test> [pending (q/find-outbound-payments-by-status
                            config
                            :outbound-payment-status-pending)
                   _ (is (= ["pmt.p2" "pmt.p4" "pmt.p1"] (payment-ids pending)))
                   completed (q/find-outbound-payments-by-status
                              config
                              :outbound-payment-status-completed)
                   _ (is (= ["pmt.p3"] (payment-ids completed)))
                   failed (q/find-outbound-payments-by-status
                           config
                           :outbound-payment-status-failed)
                   _ (is (= [] failed))]))
     (testing "inbound payments save in several statuses and banks"
       (nom-test> [_ (store/save-inbound-payment
                      config
                      (inbound "pmt.l1" "bnk.test"
                               :inbound-payment-status-suspended 1000))
                   _ (store/save-inbound-payment
                      config
                      (inbound "pmt.l2" "bnk.test"
                               :inbound-payment-status-suspended 2000))
                   _ (store/save-inbound-payment
                      config
                      (inbound "pmt.l3" "bnk.test"
                               :inbound-payment-status-suspended 3000))
                   _ (store/save-inbound-payment
                      config
                      (inbound "pmt.l4" "bnk.test"
                               :inbound-payment-status-settled 4000))
                   _ (store/save-inbound-payment
                      config
                      (inbound "pmt.l5" "bnk.other"
                               :inbound-payment-status-suspended 5000))]))
     (testing "inbound payments list by bank and status, newest first"
       (nom-test> [suspended (q/list-inbound-payments
                              config
                              "bnk.test"
                              :inbound-payment-status-suspended)
                   _ (is (= ["pmt.l3" "pmt.l2" "pmt.l1"]
                            (payment-ids suspended)))
                   settled (q/list-inbound-payments
                            config
                            "bnk.test"
                            :inbound-payment-status-settled)
                   _ (is (= ["pmt.l4"] (payment-ids settled)))
                   other (q/list-inbound-payments
                          config
                          "bnk.other"
                          :inbound-payment-status-suspended)
                   _ (is (= ["pmt.l5"] (payment-ids other)))])))))
