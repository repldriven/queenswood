(ns com.repldriven.queenswood.transaction.store-test
  (:require
    [com.repldriven.queenswood.transaction.test-system]

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
   :transaction-type :transaction-type-internal-transfer
   :currency "GBP"
   :created-at (utility/now)})

(defn- leg
  [transaction leg-id account-id side amount]
  (let [{:keys [bank-id transaction-id currency]} transaction]
    {:bank-id bank-id
     :account-id account-id
     :transaction-id transaction-id
     :leg-id leg-id
     :balance-type :balance-type-default
     :balance-status :balance-status-posted
     :side side
     :amount amount
     :currency currency}))

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

(deftest legs-summed-and-paged-by-bank-test
  (with-test-system
   [sys "classpath:transaction/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         first-txn (transaction "txn.s1" "bnk.sums" "idem-sums-1")
         second-txn (transaction "txn.s2" "bnk.sums" "idem-sums-2")]
     (testing "two transactions' legs save"
       (nom-test> [_ (store/save-transaction-and-legs
                      config
                      first-txn
                      [(leg first-txn "leg.s1a" "acc.from" :leg-side-debit 700)
                       (leg first-txn "leg.s1b" "acc.to" :leg-side-credit 700)])
                   _
                   (store/save-transaction-and-legs
                    config
                    (assoc second-txn :reference "second")
                    [(leg second-txn "leg.s2a" "acc.from" :leg-side-debit 300)
                     (leg second-txn "leg.s2b" "acc.to" :leg-side-credit 300)])]))
     (testing "an account's legs sum by side within its bank"
       (nom-test> [sums (store/sum-legs config
                                        "bnk.sums"
                                        "acc.to" :balance-type-default
                                        :balance-status-posted :serializable)
                   _ (is (= {:credit 1000 :debit 0} sums))
                   sums (store/sum-legs config
                                        "bnk.sums"
                                        "acc.from" :balance-type-default
                                        :balance-status-posted :snapshot)
                   _ (is (= {:credit 0 :debit 1000} sums))]))
     (testing "another bank sums none of the account's legs"
       (nom-test> [sums (store/sum-legs config
                                        "bnk.other"
                                        "acc.to" :balance-type-default
                                        :balance-status-posted :snapshot)
                   _ (is (= {:credit 0 :debit 0} sums))]))
     (testing "a page of an account's legs carries each transaction's details"
       (nom-test> [{:keys [transactions]}
                   (store/page-transactions config "bnk.sums" "acc.to" {})
                   _ (is (= ["txn.s2" "txn.s1"]
                            (mapv :transaction-id transactions)))
                   _ (is (= ["second" nil] (mapv :reference transactions)))
                   _ (is (every? :created-at transactions))
                   _ (is (every? (fn [t]
                                   (= :transaction-type-internal-transfer
                                      (:transaction-type t)))
                                 transactions))
                   {:keys [transactions]}
                   (store/page-transactions config "bnk.other" "acc.to" {})
                   _ (is (empty? transactions))])))))
