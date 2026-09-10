(ns ^:eftest/synchronized
    com.repldriven.queenswood.cash-account-product.interface-test
  "The write brick against a real record store: what the domain tests
  cannot see because it lives in FoundationDB rather than in a pure
  function — the unique idempotency-key index and the read-back that
  turns a retried create into the original version, the key surviving
  an update, both count indexes, the template store's re-seed, and two
  concurrent creates against a bank one below its cap.

  The domain-level rejection paths live in `domain-test`. HTTP
  behaviour is pinned by cash-account-products/*.edn in
  test-api-scenarios."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.cash-account-product.interface :as SUT]

    [com.repldriven.queenswood.cash-account-product-query.interface :as q]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]])
  (:import
    (java.util.concurrent CountDownLatch TimeUnit)))

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(def ^:private config-file
  "classpath:cash-account-product/application-test.yml")

(def ^:private current-template-id "tpl.00000000000000000000000001")
(def ^:private savings-template-id "tpl.00000000000000000000000002")
(def ^:private own-funds-template-id "tpl.00000000000000000000000004")

(def ^:private allow-draft
  "Capability without limits — the tests that are not about the cap
  reach the write without a policy read against the store."
  [{:enabled true
    :capabilities [{:effect :effect-allow
                    :kind {:cash-account-product
                           {:action :cash-account-product-action-draft}}}]}])

(defn- product-data
  ([name template-id] (product-data name template-id nil))
  ([name template-id idempotency-key]
   (cond-> {:name name
            :template-id template-id
            :currency "GBP"
            :effective-from 20089}
           idempotency-key
           (assoc :idempotency-key idempotency-key))))

;; `with-redefs` alters a root binding, so a stub here is visible to
;; every namespace beside this one. The stub consults a thread-local,
;; so a thread that did not ask to be gated gets the real function.
(def ^:private ^:dynamic *count-gate* nil)

(def ^:private real-count-by-org-product-type q/count-by-org-product-type)

(defn- gated-count-by-org-product-type
  [txn bank-id product-type]
  (let [result (real-count-by-org-product-type txn bank-id product-type)]
    (when-let [^CountDownLatch gate *count-gate*]
      (.countDown gate)
      (.await gate 5 TimeUnit/SECONDS))
    result))

(deftest retried-create-reads-the-original-back-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.retried.create"
         key "ik-product-retry-00000001"]
     (nom-test> [first-version (SUT/new-product
                                config
                                bank-id
                                (product-data "Current" current-template-id key)
                                {:policies allow-draft})
                 retried (SUT/new-product
                          config
                          bank-id
                          (product-data "Current again" current-template-id key)
                          {:policies allow-draft})
                 _ (testing
                     "the retry answers with the version the first created"
                     (is (= (:version-id first-version) (:version-id retried)))
                     (is (= (:product-id first-version) (:product-id retried)))
                     (is (= "Current" (:name retried))))
                 listed (q/get-products config bank-id)
                 _ (testing "and leaves one product behind, not two"
                     (is (= 1 (count (:items listed)))))
                 found (q/find-version-by-idempotency-key config bank-id key)
                 _ (testing "the key is indexed against that version"
                     (is (= (:version-id first-version) (:version-id found))))]))))

(deftest key-survives-an-update-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.key.survives"
         key "ik-product-update-0000001"]
     (nom-test> [created (SUT/new-product
                          config
                          bank-id
                          (product-data "Current" current-template-id key)
                          {:policies allow-draft})
                 updated (SUT/update-draft config
                                           bank-id
                                           (:product-id created)
                                           (:version-id created)
                                           (product-data "Renamed"
                                                         current-template-id)
                                           {:policies allow-draft})
                 _ (testing "the update carries the key through"
                     (is (= "Renamed" (:name updated)))
                     (is (= key (:idempotency-key updated))))
                 replayed (SUT/new-product config
                                           bank-id
                                           (product-data "Current again"
                                                         current-template-id
                                                         key)
                                           {:policies allow-draft})
                 _ (testing "so replaying the create still finds the original"
                     (is (= (:version-id created) (:version-id replayed))))
                 listed (q/get-products config bank-id)
                 _ (is (= 1 (count (:items listed))))]))))

(deftest count-indexes-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.counts"]
     (nom-test> [_ (SUT/new-product config
                                    bank-id
                                    (product-data "Current" current-template-id)
                                    {:policies allow-draft})
                 _ (testing "one product of one type"
                     (is (= 1 (q/count-by-org config bank-id)))
                     (is (= 1
                            (q/count-by-org-product-type
                             config
                             bank-id
                             :product-type-sub-ledger-current))))
                 _ (SUT/new-product config
                                    bank-id
                                    (product-data "Savings" savings-template-id)
                                    {:policies allow-draft})
                 _ (testing "a second type moves the total, not the first type"
                     (is (= 2 (q/count-by-org config bank-id)))
                     (is (= 1
                            (q/count-by-org-product-type
                             config
                             bank-id
                             :product-type-sub-ledger-current)))
                     (is (= 1
                            (q/count-by-org-product-type
                             config
                             bank-id
                             :product-type-sub-ledger-savings))))
                 ;; The own-funds template is the one new-bank creates a
                 ;; bank's house product from. No bank system boots here,
                 ;; so this is what such a product does to the counts
                 ;; without the bank brick to make one.
                 own-funds (SUT/new-product config
                                            bank-id
                                            (product-data "Bank own funds"
                                                          own-funds-template-id)
                                            {:policies allow-draft})
                 _ (testing
                     "an own-funds product counts toward the bank's total"
                     (is (true? (:internal own-funds)))
                     (is (= 3 (q/count-by-org config bank-id)))
                     (is (= 1
                            (q/count-by-org-product-type
                             config
                             bank-id
                             :product-type-sub-ledger-own-funds))))
                 listed (q/get-products config bank-id)
                 _ (testing "while the listing hides it"
                     (is (= 2 (count (:items listed))))
                     (is (not-any? #(= (:product-id own-funds) (:product-id %))
                                   (:items listed))))
                 by-id (q/get-product config bank-id (:product-id own-funds))
                 _ (testing "and it is still reachable by id"
                     (is (= (:product-id own-funds) (:product-id by-id))))]))))

(deftest template-round-trips-and-re-seeds-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         template
         {:template-id "tpl.00000000000000000000000099"
          :name "Round Trip Current"
          :product-type :product-type-sub-ledger-current
          :balance-sheet-side :balance-sheet-side-liability
          :iso-cash-account-type :iso-cash-account-type-cacc
          :allowed-currencies ["GBP"]
          :allowed-payment-address-schemes [:payment-address-scheme-scan]
          :balance-products [{:balance-type :balance-type-default
                              :balance-status :balance-status-posted}]}]
     (nom-test> [seeded (SUT/new-template config template)
                 loaded (q/get-template config (:template-id template))
                 _ (testing "the template round-trips through the store"
                     (is (= (:template-id template) (:template-id loaded)))
                     (is (= "Round Trip Current" (:name loaded)))
                     (is (= :product-type-sub-ledger-current
                            (:product-type loaded)))
                     (is (= ["GBP"] (:allowed-currencies loaded)))
                     (is (= (:created-at seeded) (:created-at loaded))))
                 _ (Thread/sleep 5)
                 re-seeded (SUT/new-template config
                                             (assoc template :name "Renamed"))
                 _ (testing "re-seeding keeps created-at and moves updated-at"
                     (is (= (:created-at seeded) (:created-at re-seeded)))
                     (is (< (:created-at re-seeded) (:updated-at re-seeded))))
                 reloaded (q/get-template config (:template-id template))
                 _ (testing "and the store holds the re-seeded values"
                     (is (= "Renamed" (:name reloaded)))
                     (is (= (:created-at seeded) (:created-at reloaded))))]))))

(deftest concurrent-creates-at-the-cap-admit-one-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.cap.race"
         ;; Both threads read the per-type count, then hold until the
         ;; other has read it too, so the two creates overlap by
         ;; construction rather than by timing.
         gate (CountDownLatch. 2)
         racing-create (fn [name]
                         (future (binding [*count-gate* gate]
                                   (SUT/new-product
                                    config
                                    bank-id
                                    (product-data name current-template-id)))))]
     (nom-test>
       ;; The micro tier caps each customer product type at one, so a
       ;; bank with no current product is one below the cap. Binding
       ;; the tier rather than passing :policies puts the policy read
       ;; inside new-product's transaction, where production has it.
       [micro (policy/get-policies-by-tier config "micro")
        _ (is (= 1 (count micro)))
        _ (policy/new-binding config
                              {:policy-id (:policy-id (first micro))
                               :target {:kind {:bank {:bank-id bank-id}}}})
        results (with-redefs [q/count-by-org-product-type
                              gated-count-by-org-product-type]
                  (let [left (racing-create "Current A")
                        right (racing-create "Current B")]
                    [@left @right]))
        versions (filterv (complement error/anomaly?) results)
        rejections (filterv error/rejection? results)
        _ (testing "exactly one create is admitted"
            (is (= 1 (count versions)))
            (is (= 1 (count rejections)))
            (is (= :policy/limit-exceeded (error/kind (first rejections)))))
        listed (q/get-products config bank-id)
        _ (testing "and the bank holds the one product it admitted"
            (is (= 1 (count (:items listed))))
            (is (= (:product-id (first versions))
                   (:product-id (first (:items listed)))))
            (is (= 1
                   (q/count-by-org-product-type
                    config
                    bank-id
                    :product-type-sub-ledger-current))))]))))
