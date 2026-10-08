(ns com.repldriven.queenswood.cash-account.interface-test
  "The provider legs, against a real store: a rotation retried under one
  idempotency key asks the provider once, and each report from the
  provider — an opening, a refusal, a reissue — lands once, however
  often it is delivered. The domain-level guards live in `domain-test`;
  the opening guards, the count limits and the non-zero close are pure
  there, and their end-to-end shape is an EDN scenario in
  `test-scenarios` or `test-api-scenarios`."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.cash-account.core :as core]
    [com.repldriven.queenswood.cash-account.interface :as SUT]
    [com.repldriven.queenswood.cash-account.store :as store]

    [com.repldriven.queenswood.cash-account-query.interface :as q]

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

(def ^:private allow-rotate
  [{:enabled true
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
   :product-type :product-type-sub-ledger-current
   :name "Provider Legs Account"
   :currency "GBP"
   :status status
   :payment-addresses []
   :created-at (utility/now)
   :updated-at (utility/now)})

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

(deftest rotate-address-retried-under-one-key-asks-once-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         account-id "acc.rotate.retry"
         key "ik-rotate-retry-00000001"
         command
         {:bank-id test-bank-id :account-id account-id :idempotency-key key}]
     (nom-test> [_ (save config
                         (account account-id :cash-account-status-opening))
                 opened (core/provider-opened config
                                              (issued account-id "20000001"))
                 requested
                 (SUT/rotate-address config command {:policies allow-rotate})
                 _ (testing "the rotation is pending and the address stays"
                     (is (= {:idempotency-key key
                             :status :address-rotation-status-pending}
                            (:rotation requested)))
                     (is (= (:bban opened) (:bban requested))))
                 retried
                 (SUT/rotate-address config command {:policies allow-rotate})
                 _ (testing "the retry answers with the account as it was"
                     (is (= (:updated-at requested) (:updated-at retried))))
                 stale (core/provider-reissued config
                                               (issued
                                                account-id
                                                "20000007"
                                                :rotation-key
                                                "ik-some-other-rotation"))
                 _ (testing "a reissue for another rotation is ignored"
                     (is (nil? stale)))
                 reissued (core/provider-reissued
                           config
                           (issued account-id "20000002" :rotation-key key))
                 _ (testing "the provider's new address replaces the old"
                     (is (= "04000420000002" (:bban reissued)))
                     (is (= 1 (count (:retired-payment-addresses reissued))))
                     (is (= :address-rotation-status-completed
                            (get-in reissued [:rotation :status]))))
                 replayed (core/provider-reissued
                           config
                           (issued account-id "20000002" :rotation-key key))
                 _ (testing "and a redelivered reissue is a no-op"
                     (is (nil? replayed)))
                 stored (q/get-account config test-bank-id account-id)
                 _ (is (= "04000420000002" (:bban stored)))
                 _ (is (= 1 (count (:retired-payment-addresses stored))))]))))
