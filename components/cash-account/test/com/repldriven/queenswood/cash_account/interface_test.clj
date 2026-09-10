(ns ^:eftest/synchronized com.repldriven.queenswood.cash-account.interface-test
  "The write brick against a real record store and a real policy
  store: an open replayed under one idempotency key, the two count
  limits — the unfiltered one hand-built and the filtered one loaded
  from the micro tier's seed — the two payment-address scheme
  rejections, the non-zero close waived by a policy record bound to
  the bank, and a rotation retried under one key.

  Pure-function guards on the same transitions live in `domain-test`;
  HTTP behaviour is pinned by cash-accounts/*.edn in
  test-api-scenarios."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.cash-account.interface :as SUT]
    [com.repldriven.queenswood.cash-account.store :as store]

    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.cash-account-product.interface :as products]
    [com.repldriven.queenswood.cash-account-query.interface :as q]
    [com.repldriven.queenswood.party.interface :as parties]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:cash-account/application-test.yml")

(def ^:private test-bank-id "bnk_rotate_retry_test")
(def ^:private test-sort-code "040404")

(def ^:private effective-from
  "An epoch day well behind any run, so a published version is active
  the day the test opens against it."
  20089)

(def ^:private unfiltered-cap
  "The bound the hand-built count limit carries. Two opens fit under
  it and the third does not."
  2)

(def ^:private seeded-term-deposit-cap
  "The bound
  components/resources/resources/policies/micro/restricted/limits/cash-accounts.yml
  puts on term-deposit accounts. The test opens that many and expects
  the next one refused."
  10)

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(defn- policy-allowing
  [kind & actions]
  {:enabled true
   :capabilities (mapv (fn [action]
                         {:effect :effect-allow
                          :kind {kind {:action action}}})
                       actions)})

(def ^:private allow-product
  [(policy-allowing :cash-account-product
                    :cash-account-product-action-draft
                    :cash-account-product-action-publish)])

(def ^:private allow-rotate
  [(policy-allowing :cash-account :cash-account-action-rotate-address)])

(def ^:private allow-open-capped
  "Open, plus an unfiltered `cash-account` count limit in the shape
  the micro seed's first entry has — small enough for a test to
  reach."
  [(assoc (policy-allowing :cash-account :cash-account-action-open)
          :limits
          [{:kind {:cash-account {}}
            :bound {:kind {:max {:aggregate {:kind {:count
                                                    {:value unfiltered-cap
                                                     :window
                                                     :time-window-instant}}}}}}
            :reason "Test - two cash accounts"}])])

(def ^:private waiver-policy
  "A policy record granting the non-zero-close capability, bound to
  one bank rather than passed in as a `:policies` override."
  {:name "Close non-zero waiver"
   :enabled true
   :category :policy-category-standard
   :capabilities [{:effect :effect-allow
                   :reason "Waive the non-zero balance check on close"
                   :kind {:cash-account {:action
                                         :cash-account-action-close-non-zero}}}]
   :limits []
   :labels {}})

(def ^:private product-type->iso
  {:product-type-sub-ledger-current :iso-cash-account-type-cacc
   :product-type-sub-ledger-term-deposit :iso-cash-account-type-llsv})

(defn- product-template
  [template-id product-type schemes]
  {:template-id template-id
   :name "Cash Account Interface Test"
   :product-type product-type
   :balance-sheet-side :balance-sheet-side-liability
   :iso-cash-account-type (product-type->iso product-type)
   :allowed-currencies ["GBP"]
   :allowed-payment-address-schemes schemes
   :balance-products [{:balance-type :balance-type-default
                       :balance-status :balance-status-posted}]})

(def ^:private current-template
  (product-template "tpl.open-current"
                    :product-type-sub-ledger-current
                    [:payment-address-scheme-scan]))

(def ^:private term-deposit-template
  (product-template "tpl.open-term-deposit"
                    :product-type-sub-ledger-term-deposit
                    [:payment-address-scheme-scan]))

(def ^:private no-scheme-template
  (product-template "tpl.open-no-schemes" :product-type-sub-ledger-current []))

(def ^:private iban-template
  (product-template "tpl.open-iban"
                    :product-type-sub-ledger-current
                    [:payment-address-scheme-iban]))

(defn- published-version
  "Create a product from `template-id` and publish its first version,
  returning that version."
  [config bank-id name template-id]
  (let-nom>
    [version (products/new-product config
                                   bank-id
                                   {:name name
                                    :template-id template-id
                                    :currency "GBP"
                                    :effective-from effective-from}
                                   {:policies allow-product})
     _ (products/publish config
                         bank-id
                         (:product-id version)
                         (:version-id version)
                         {:policies allow-product})]
    version))

(defn- account-holder
  "An organisation party, which is born active and so can hold an
  account without an IDV leg."
  [config bank-id]
  (parties/new-party config
                     {:bank-id bank-id
                      :type :party-type-organization
                      :display-name "Interface Test Holder"}))

(defn- open-data
  [bank-id party-id product-id]
  {:bank-id bank-id
   :party-id party-id
   :product-id product-id
   :currency "GBP"
   :sort-code test-sort-code
   :name "Interface Test Account"})

(deftest open-replayed-under-one-key-opens-one-account-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.open.replay"
         key "ik-open-replay-00000001"]
     (nom-test> [_ (products/new-template config current-template)
                 party (account-holder config bank-id)
                 version (published-version config
                                            bank-id
                                            "Replay Current"
                                            (:template-id current-template))
                 data (assoc (open-data bank-id
                                        (:party-id party)
                                        (:product-id version))
                             :idempotency-key
                             key)
                 opened (SUT/new-account config data)
                 replayed (SUT/new-account config data)
                 _ (testing "the replay answers with the first open's account"
                     (is (= (:account-id opened) (:account-id replayed)))
                     (is (= key (:idempotency-key replayed))))
                 listed (q/get-accounts config bank-id)
                 _ (testing "and the bank holds one account, not two"
                     (is (= 1 (count (:accounts listed)))))]))))

(deftest open-refused-at-the-unfiltered-count-limit-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.open.total.cap"]
     (nom-test> [_ (products/new-template config current-template)
                 party (account-holder config bank-id)
                 version (published-version config
                                            bank-id
                                            "Capped Current"
                                            (:template-id current-template))
                 data
                 (open-data bank-id (:party-id party) (:product-id version))
                 admitted
                 (mapv
                  (fn [_]
                    (SUT/new-account config data {:policies allow-open-capped}))
                  (range unfiltered-cap))
                 _ (testing "the bank opens up to the bound"
                     (is (= unfiltered-cap
                            (count (remove error/anomaly? admitted)))))
                 _ (testing "and the open past it is refused"
                     (let [refused (SUT/new-account config
                                                    data
                                                    {:policies
                                                     allow-open-capped})]
                       (is (error/rejection? refused))
                       (is (= :policy/limit-exceeded (error/kind refused)))))]))))

(deftest open-refused-at-the-seeded-term-deposit-limit-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.open.term.cap"]
     (nom-test> [micro (policy/get-policies-by-tier config "micro")
                 _ (is (= 1 (count micro)))
                 _ (policy/new-binding config
                                       {:policy-id (:policy-id (first micro))
                                        :target {:kind {:bank {:bank-id
                                                               bank-id}}}})
                 _ (products/new-template config current-template)
                 _ (products/new-template config term-deposit-template)
                 party (account-holder config bank-id)
                 term (published-version config
                                         bank-id
                                         "Micro Term Deposit"
                                         (:template-id term-deposit-template))
                 current (published-version config
                                            bank-id
                                            "Micro Current"
                                            (:template-id current-template))
                 term-data
                 (open-data bank-id (:party-id party) (:product-id term))
                 admitted (mapv (fn [_] (SUT/new-account config term-data))
                                (range seeded-term-deposit-cap))
                 _ (testing "the seeded bound admits its whole allowance"
                     (is (= seeded-term-deposit-cap
                            (count (remove error/anomaly? admitted)))))
                 _ (testing "and refuses the open past it"
                     (let [refused (SUT/new-account config term-data)]
                       (is (error/rejection? refused))
                       (is (= :policy/limit-exceeded (error/kind refused)))))
                 other (SUT/new-account config
                                        (open-data bank-id
                                                   (:party-id party)
                                                   (:product-id current)))
                 _ (testing "while the filter leaves another product type alone"
                     (is (= :cash-account-status-opening
                            (:account-status other))))]))))

(deftest open-refused-when-the-version-allows-no-scheme-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.open.no.schemes"]
     (nom-test> [_ (products/new-template config no-scheme-template)
                 party (account-holder config bank-id)
                 version (published-version config
                                            bank-id
                                            "No Schemes"
                                            (:template-id no-scheme-template))
                 _ (testing "a version allowing no scheme cannot be addressed"
                     (let [refused (SUT/new-account config
                                                    (open-data bank-id
                                                               (:party-id party)
                                                               (:product-id
                                                                version)))]
                       (is (error/rejection? refused))
                       (is (= :cash-account/no-payment-schemes
                              (error/kind refused)))))]))))

(deftest open-refused-when-the-version-allows-only-another-scheme-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.open.iban"]
     (nom-test> [_ (products/new-template config iban-template)
                 party (account-holder config bank-id)
                 version (published-version config
                                            bank-id
                                            "IBAN Only"
                                            (:template-id iban-template))
                 _ (testing "a scheme the fountain cannot allocate is named"
                     (let [refused (SUT/new-account config
                                                    (open-data bank-id
                                                               (:party-id party)
                                                               (:product-id
                                                                version)))]
                       (is (error/rejection? refused))
                       (is (= :cash-account/unsupported-scheme
                              (error/kind refused)))))]))))

(deftest close-non-zero-waived-by-a-bound-policy-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk.close.waiver"]
     (nom-test> [_ (products/new-template config current-template)
                 party (account-holder config bank-id)
                 version (published-version config
                                            bank-id
                                            "Waiver Current"
                                            (:template-id current-template))
                 opened (SUT/new-account config
                                         (open-data bank-id
                                                    (:party-id party)
                                                    (:product-id version)))
                 account-id (:account-id opened)
                 close-data {:bank-id bank-id :account-id account-id}
                 _ (SUT/seed-opened-account config bank-id account-id)
                 _ (balances/apply-legs config
                                        bank-id
                                        [{:account-id account-id
                                          :balance-type :balance-type-default
                                          :balance-status :balance-status-posted
                                          :side :leg-side-credit
                                          :amount 500}]
                                        :transaction-type-fee)
                 _ (testing "the money in the bucket refuses the close"
                     (let [refused (SUT/close-account config close-data)]
                       (is (error/rejection? refused))
                       (is (= :cash-account/non-zero-on-close
                              (error/kind refused)))))
                 waiver (policy/new-policy config waiver-policy)
                 _ (policy/new-binding config
                                       {:policy-id (:policy-id waiver)
                                        :target {:kind {:bank {:bank-id
                                                               bank-id}}}})
                 closed (SUT/close-account config close-data)
                 _ (testing "and the waiver granted on record lets it through"
                     (is (= :cash-account-status-closing
                            (:account-status closed))))]))))

(defn- opened-account
  [version account-number]
  {:bank-id test-bank-id
   :account-id "acc.rotate.retry"
   :account-type :account-type-personal
   :party-id "pty.test"
   :product-id (:product-id version)
   :version-id (:version-id version)
   :product-type (:product-type version)
   :name "Rotation Retry Account"
   :currency "GBP"
   :account-status :cash-account-status-opened
   :payment-addresses [{:scheme :payment-address-scheme-scan
                        :scan {:sort-code test-sort-code
                               :account-number account-number}}]
   :bban (str test-sort-code account-number)
   :created-at (utility/now)
   :updated-at (utility/now)})

(defn- account-number-of
  "The account's scan account-number. Read through rather than
  compared whole, because the skip answers with the stored record and
  a fresh rotation answers with the map the domain built — same
  address, two shapes."
  [account]
  (get-in account [:payment-addresses 0 :scan :account-number]))

(deftest rotate-address-retried-under-one-key-allocates-once-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         key "ik-rotate-retry-00000001"
         command {:bank-id test-bank-id
                  :account-id "acc.rotate.retry"
                  :idempotency-key key}]
     (nom-test> [_ (products/new-template config current-template)
                 version (published-version config
                                            test-bank-id
                                            "Rotation Retry Current"
                                            (:template-id current-template))
                 account-number (store/allocate-payment-address config
                                                                test-sort-code)
                 _ (store/save-account
                    config
                    (opened-account version account-number)
                    {:account-id "acc.rotate.retry"
                     :status-after :cash-account-status-opened
                     :change-kind :cash-account-change-kind-open})
                 rotated
                 (SUT/rotate-address config command {:policies allow-rotate})
                 _ (testing "the first rotation allocates and retires"
                     (is (= 1 (count (:retired-payment-addresses rotated))))
                     (is (not= account-number (account-number-of rotated)))
                     (is (= key (:last-rotation-idempotency-key rotated))))
                 before (store/allocate-payment-address config test-sort-code)
                 retried
                 (SUT/rotate-address config command {:policies allow-rotate})
                 after (store/allocate-payment-address config test-sort-code)
                 _ (testing
                     "the retry answers with the address the first allocated"
                     (is (= (account-number-of rotated)
                            (account-number-of retried)))
                     (is (= (:bban rotated) (:bban retried))))
                 _ (testing "and retires nothing more"
                     (is (= (count (:retired-payment-addresses rotated))
                            (count (:retired-payment-addresses retried)))))
                 _ (testing "and takes no number from the fountain"
                     (is (= (inc (Long/parseLong before))
                            (Long/parseLong after))))
                 stored (q/get-account config test-bank-id "acc.rotate.retry")
                 _ (testing
                     "leaving the stored account exactly as the first left it"
                     (is (= (account-number-of rotated)
                            (account-number-of stored)))
                     (is (= (:updated-at rotated) (:updated-at stored))))]))))
