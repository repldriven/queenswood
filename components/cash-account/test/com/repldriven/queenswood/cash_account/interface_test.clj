(ns ^:eftest/synchronized com.repldriven.queenswood.cash-account.interface-test
  "The rotation retry, against a real store: `rotate-address` driven
  twice under one idempotency key allocates one set of addresses. The
  domain-level guards live in `domain_test`; this is the part only a
  store and a payment-address fountain can show."
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.cash-account.interface :as SUT]
    [com.repldriven.queenswood.cash-account.store :as store]

    [com.repldriven.queenswood.cash-account-product.interface :as products]
    [com.repldriven.queenswood.cash-account-query.interface :as q]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private test-bank-id "bnk_rotate_retry_test")
(def ^:private test-sort-code "040404")

(defn- policy-allowing
  [kind & actions]
  {:enabled true
   :capabilities (mapv (fn [action]
                         {:effect :effect-allow
                          :kind {kind {:action action}}})
                       actions)})

(def ^:private allow-draft
  [(policy-allowing :cash-account-product :cash-account-product-action-draft)])

(def ^:private allow-rotate
  [(policy-allowing :cash-account :cash-account-action-rotate-address)])

(def ^:private template
  {:template-id "tpl.rotate-retry-current"
   :name "Rotation Retry Current"
   :product-type :product-type-sub-ledger-current
   :balance-sheet-side :balance-sheet-side-liability
   :iso-cash-account-type :iso-cash-account-type-cacc
   :allowed-currencies ["GBP"]
   :allowed-payment-address-schemes [:payment-address-scheme-scan]
   :balance-products [{:balance-type :balance-type-default
                       :balance-status :balance-status-posted}]})

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
   [sys "classpath:cash-account/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}
         key "ik-rotate-retry-00000001"
         command {:bank-id test-bank-id
                  :account-id "acc.rotate.retry"
                  :idempotency-key key}]
     (nom-test> [_ (products/new-template config template)
                 version (products/new-product config
                                               test-bank-id
                                               {:name "Rotation Retry Current"
                                                :template-id (:template-id
                                                              template)
                                                :currency "GBP"
                                                :effective-from 20089}
                                               {:policies allow-draft})
                 account-number (store/allocate-payment-address config
                                                                test-sort-code)
                 _ (store/save-account config
                                       (opened-account version account-number)
                                       {:account-id "acc.rotate.retry"
                                        :status-after
                                        :cash-account-status-opened})
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
