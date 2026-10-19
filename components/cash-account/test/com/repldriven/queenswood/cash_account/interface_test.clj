(ns com.repldriven.queenswood.cash-account.interface-test
  "The provider legs, against a real store: a rotation retried under one
  idempotency key asks the provider once, each report from the provider
  — an opening, a refusal, a reissue — lands once, however often it is
  delivered, and a refused close returns the account to where it was. The domain-level guards live in `domain-test`;
  the opening guards, the count limits and the non-zero close are pure
  there, and their end-to-end shape is an EDN scenario in
  `test-scenarios` or `test-api-scenarios`."
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.cash-account.core :as core]
    [com.repldriven.queenswood.cash-account.interface :as SUT]
    [com.repldriven.queenswood.cash-account.store :as store]

    [com.repldriven.queenswood.cash-account-query.interface :as q]
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:cash-account/application-test.yml")

(def ^:private test-bank-id "bnk_provider_legs_test")

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(def ^:private member {:kind :actor-kind-member :principal-id "usr.1"})

(def ^:private allow-rotate
  [{:status :policy-status-active
    :capabilities [{:effect :effect-allow
                    :kind {:cash-account
                           {:action :cash-account-action-rotate-address}}}]}])

(defn- account
  [account-id status]
  {:bank-id test-bank-id
   :account-id account-id
   :account-type :account-type-personal
   :party-id "pty.test"
   :product-id "prd.test"
   :version-id "prv.test"
   :version-from-on 20089
   :created-by {:kind :actor-kind-operator :principal-id "test"}
   :product-type :account-product-type-sub-ledger-current
   :name "Provider Legs Account"
   :currency "GBP"
   :status status
   :payment-addresses []
   :created-at (utility/now)
   :updated-at (utility/now)})

(defn- save-version
  "The version the test's accounts are on, which a close reads its
  closure terms from."
  [config]
  (fdb/transact
   config
   (fn [txn]
     (fdb/save-record (fdb/open txn "cash-account-products")
                      (schema/CashAccountProduct->java
                       {:bank-id test-bank-id
                        :product-id "prd.test"
                        :version-id "prv.test"
                        :version-number 1
                        :status :version-status-published
                        :product-type :account-product-type-sub-ledger-current
                        :balance-sheet-side :balance-sheet-side-liability
                        :name "Provider Legs Account"
                        :currency "GBP"
                        :template-id "tpl.00000000000000000000000001"
                        :iso-cash-account-type :iso-cash-account-type-cacc
                        :balance-products [{:balance-type :balance-type-default
                                            :balance-status
                                            :balance-status-posted}]
                        :closure-terms {:interest {:kind {:pay {}}}}
                        :effective-from 20089
                        :created-at (utility/now)
                        :created-by {:kind :actor-kind-operator
                                     :principal-id "test"}})))
   :test/seed
   "Failed to seed the version"))

(defn- save
  [config account]
  (store/save-account config
                      account
                      {:account-id (:account-id account)
                       :status-after (:status account)
                       :change-kind :cash-account-change-kind-open}))

(defn- issued
  [account-id account-number & {:as extra}]
  (merge {:bank-id test-bank-id
          :account-id account-id
          :provider-account-id "va-1"
          :addresses [{:scheme "scan"
                       :sort-code "040004"
                       :account-number account-number}]}
         extra))

(deftest provider-opening-lands-once-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         account-id "acc.provider.open"]
     (nom-test> [_ (save config
                         (account account-id :cash-account-status-opening))
                 opened (core/provider-opened config
                                              (issued account-id "20000001"))
                 _ (testing "the provider's address opens the account"
                     (is (= :cash-account-status-opened (:status opened)))
                     (is (= "04000420000001" (:bban opened)))
                     (is (= "va-1" (:provider-account-id opened))))
                 again (core/provider-opened config
                                             (issued account-id "20000009"))
                 _ (testing "a redelivered opening is a no-op"
                     (is (nil? again)))
                 refused (core/provider-refused config
                                                {:bank-id test-bank-id
                                                 :account-id account-id
                                                 :reason "Declined"})
                 _ (testing "and a refusal after it changes nothing"
                     (is (nil? refused)))
                 stored (q/get-account config test-bank-id account-id)
                 _ (is (= "04000420000001" (:bban stored)))
                 _ (is (= :cash-account-status-opened (:status stored)))]))))

(defn- operation-under
  [config account-id idempotency-key]
  (q/find-operation-by-idempotency-key config
                                       test-bank-id
                                       account-id
                                       idempotency-key))

(deftest rotate-address-retried-under-one-key-asks-once-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         account-id "acc.rotate.retry"
         key "ik-rotate-retry-00000001"
         command {:bank-id test-bank-id
                  :account-id account-id
                  :idempotency-key key
                  :actor member}]
     (nom-test> [_ (save config
                         (account account-id :cash-account-status-opening))
                 opened (core/provider-opened config
                                              (issued account-id "20000001"))
                 requested
                 (SUT/rotate-address config command {:policies allow-rotate})
                 _ (testing "the address stays until the provider answers"
                     (is (= (:bban opened) (:bban requested))))
                 operation (operation-under config account-id key)
                 _ (testing "and the rotation waits on it as an operation"
                     (is (= :cash-account-operation-kind-rotate-address
                            (:kind operation)))
                     (is (= :cash-account-operation-status-pending
                            (:status operation))))
                 retried
                 (SUT/rotate-address config command {:policies allow-rotate})
                 _ (testing "the retry answers with the account as it was"
                     (is (= (:updated-at requested) (:updated-at retried))))
                 asked (q/count-operations config
                                           test-bank-id
                                           account-id
                                           (:requested-on operation))
                 _ (testing "and asks for no second operation" (is (= 1 asked)))
                 stale
                 (core/provider-reissued
                  config
                  (issued account-id "20000007" :operation-id "cao.some-other"))
                 _ (testing "a reissue for another operation is ignored"
                     (is (nil? stale)))
                 answer (issued account-id
                                "20000002"
                                :operation-id
                                (:operation-id operation))
                 reissued (core/provider-reissued config answer)
                 _ (testing "the provider's new address replaces the old"
                     (is (= "04000420000002" (:bban reissued)))
                     (is (= 1 (count (:retired-payment-addresses reissued)))))
                 settled (operation-under config account-id key)
                 _ (testing "and completes the operation"
                     (is (= :cash-account-operation-status-completed
                            (:status settled))))
                 replayed (core/provider-reissued config answer)
                 _ (testing "and a redelivered reissue is a no-op"
                     (is (nil? replayed)))
                 stored (q/get-account config test-bank-id account-id)
                 _ (is (= "04000420000002" (:bban stored)))
                 _ (is (= 1 (count (:retired-payment-addresses stored))))]))))

(def ^:private allow-close
  [{:status :policy-status-active
    :capabilities [{:effect :effect-allow
                    :kind {:cash-account {:action
                                          :cash-account-action-close}}}]}])

(deftest a-refused-close-returns-the-account-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         account-id "acc.close.refused"
         key "ik-close-refused-00000001"]
     (nom-test> [_ (save-version config)
                 _ (save config
                         (account account-id :cash-account-status-opening))
                 _ (core/provider-opened config (issued account-id "20000003"))
                 closing (SUT/close-account config
                                            {:bank-id test-bank-id
                                             :account-id account-id
                                             :idempotency-key key
                                             :actor member}
                                            {:policies allow-close})
                 _ (is (= :cash-account-status-closing (:status closing)))
                 operation (operation-under config account-id key)
                 _ (testing "the close remembers the status it was asked from"
                     (is (= :cash-account-status-opened
                            (:account-status operation))))
                 _ (testing "and the interest term its version closes it by"
                     (is (some? (:closure-interest operation))))
                 refused (core/provider-close-refused
                          config
                          {:bank-id test-bank-id
                           :account-id account-id
                           :operation-id (:operation-id operation)
                           :reason "Money still moving"})
                 _ (testing "a refusal returns the account to it"
                     (is (= :cash-account-status-opened (:status refused))))
                 settled (operation-under config account-id key)
                 _ (testing "and refuses the operation with the reason"
                     (is (= :cash-account-operation-status-refused
                            (:status settled)))
                     (is (= "Money still moving" (:refused-reason settled))))]))))
