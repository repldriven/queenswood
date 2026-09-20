(ns ^:eftest/synchronized com.repldriven.queenswood.reward.interface-test
  "The reward pass against a real record store and a bank laid down the
  way provisioning leaves one: its chart seeded through the ledger
  brick, and its own-funds version and house account, a rewarding
  version and a plain one, and a customer account under each written
  straight into the stores in the shape the write bricks leave them —
  reaching the same rows by driving those bricks is the scenarios' job,
  and not every project this brick runs in carries them. What the
  domain tests cannot see is the posting's refusal when the house
  account cannot cover the reward, the row it leaves, and the run that
  pays it once the bank is funded."
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.reward.interface :as SUT]
    [com.repldriven.queenswood.reward.store :as store]

    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.schema.interface :as schema]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:reward/application-test.yml")

(def ^:private sort-code "040404")

;; must match cash-account.store and cash-account-product.store — the
;; stores the pass reads
(def ^:private accounts-store "cash-accounts")
(def ^:private products-store "cash-account-products")
(def ^:private balances-store "balances")

(def ^:private allow-ledger
  "The chart's create capability, with no limits. The pass itself
  resolves the bank's policies from the store, which are the platform's,
  whose available-balance limit is the refusal under test."
  [{:enabled true
    :capabilities [{:kind {:ledger-account {}} :effect :effect-allow}]}])

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(defn- version
  "A published version as `cash-account-product/domain` builds it."
  [bank-id product-id product-type data]
  (let [now (utility/now)]
    (merge {:bank-id bank-id
            :product-id product-id
            :version-id (str "prv." product-id)
            :version-number 1
            :status :cash-account-product-status-published
            :product-type product-type
            :balance-sheet-side :balance-sheet-side-liability
            :name product-id
            :allowed-currencies ["GBP"]
            :balance-products [{:balance-type :balance-type-default
                                :balance-status :balance-status-posted}]
            :effective-from 20089
            :created-at now
            :updated-at now}
           data)))

(defn- account
  "An opened account as `cash-account/domain` builds it. The bban is a
  unique index, so `account-number` is unique per account."
  [bank-id account-id party-id version account-number]
  (let [now (utility/now)]
    {:bank-id bank-id
     :account-id account-id
     :account-type :account-type-business
     :party-id party-id
     :product-id (:product-id version)
     :version-id (:version-id version)
     :product-type (:product-type version)
     :name account-id
     :currency "GBP"
     :account-status :cash-account-status-opened
     :payment-addresses [{:scheme :payment-address-scheme-scan
                          :scan {:sort-code sort-code
                                 :account-number account-number}}]
     :bban (str sort-code account-number)
     :created-at now
     :updated-at now}))

(defn- balance
  "The posted default bucket an account opens with, before any leg. The
  available-balance limit reads a bucket that exists, which is what
  refuses the unfunded house account."
  [account]
  (let [now (utility/now)]
    {:bank-id (:bank-id account)
     :account-id (:account-id account)
     :product-type (:product-type account)
     :balance-type :balance-type-default
     :balance-status :balance-status-posted
     :currency (:currency account)
     :credit 0
     :debit 0
     :credit-carry 0
     :created-at now
     :updated-at now}))

(defn- seed-rows
  "Versions, accounts and each account's opening bucket, in one
  transaction."
  [config versions accounts]
  (fdb/transact config
                (fn [txn]
                  (let [products (fdb/open txn products-store)
                        accounts-store (fdb/open txn accounts-store)
                        balances (fdb/open txn balances-store)]
                    (doseq [v versions]
                      (fdb/save-record products
                                       (schema/CashAccountProduct->java v)))
                    (doseq [a accounts]
                      (fdb/save-record accounts-store
                                       (schema/CashAccount->java a))
                      (fdb/save-record balances
                                       (schema/Balance->java (balance a))))
                    nil))
                :test/seed
                "Failed to seed rows"))

(defn- seed-chart
  "The bank's default ledger accounts in GBP, as provisioning seeds
  them."
  [config bank-id]
  (reduce (fn [_ row]
            (let [result (ledger-accounts/new-account config
                                                      bank-id
                                                      "GBP"
                                                      row
                                                      {:policies allow-ledger})]
              (if (error/anomaly? result) (reduced result) nil)))
          nil
          (edn/read-string (slurp (io/resource "ledgers/general-ledger.edn")))))

(defn- fund-house
  "Money from outside into the house account, the way the simulate
  route lands it: a debit on 1100 and a credit on the house."
  [config bank-id house amount]
  (let-nom> [cash (ledger-accounts/find-by-code
                   config
                   bank-id
                   :gl-account-code-cash-at-correspondent
                   "GBP")
             legs (ledger-accounts/add-control-legs
                   config
                   bank-id
                   "GBP"
                   [{:account-id (:ledger-account-id cash)
                     :balance-type :balance-type-default
                     :balance-status :balance-status-posted
                     :side :leg-side-debit
                     :amount amount}
                    {:account-id (:account-id house)
                     :product-type (:product-type house)
                     :balance-type :balance-type-default
                     :balance-status :balance-status-posted
                     :side :leg-side-credit
                     :amount amount}])
             posted (transactions/record-and-post
                     config
                     bank-id
                     {:idempotency-key (str "fund-" bank-id)
                      :transaction-type :transaction-type-inbound-transfer
                      :currency "GBP"
                      :reference "Funding"
                      :legs legs})]
    posted))

(defn- run
  [config bank-id]
  (SUT/pay-due config {:bank-id bank-id :as-of-date (utility/today)}))

(defn- row
  [config bank-id account]
  (store/find-by-account config
                         bank-id
                         (:account-id account)
                         :reward-kind-opening))

(deftest an-unfunded-house-defers-and-a-funded-one-pays-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id (str "bnk.reward." (utility/uuidv7))
         house-version (version bank-id
                                "prd.house"
                                :product-type-sub-ledger-own-funds
                                {:name "Bank own funds" :internal true})
         rewarding (version bank-id
                            "prd.welcome"
                            :product-type-sub-ledger-current
                            {:opening-reward {:amount 1000}})
         plain (version bank-id "prd.plain" :product-type-sub-ledger-savings {})
         house (account bank-id "acc.house" "pty.bank" house-version "10000041")
         rewarded
         (account bank-id "acc.welcome" "pty.cust" rewarding "10000042")
         unrewarded (account bank-id "acc.plain" "pty.cust" plain "10000043")]
     (nom-test> [_ (seed-chart config bank-id)
                 _ (seed-rows config
                              [house-version rewarding plain]
                              [house rewarded unrewarded])
                 first-run (run config bank-id)
                 _ (testing "an unfunded house account leaves the reward due"
                     (is (= {:accounts-processed 0 :accounts-failed 1}
                            (select-keys first-run
                                         [:accounts-processed
                                          :accounts-failed]))))
                 due (row config bank-id rewarded)
                 _ (testing "with the row saying why"
                     (is (= :reward-status-due (:status due)))
                     (is (= 1000 (:amount due)))
                     (is (string? (:error due)))
                     (is (not (contains? due :transaction-id))))
                 _ (testing
                     "and no row at all under a version that promised none"
                     (is (nil? (row config bank-id unrewarded))))
                 _ (fund-house config bank-id house 5000)
                 second-run (run config bank-id)
                 _ (testing "the next run pays it"
                     (is (= {:accounts-processed 1 :accounts-failed 0}
                            (select-keys second-run
                                         [:accounts-processed
                                          :accounts-failed]))))
                 paid (row config bank-id rewarded)
                 _ (testing "as one row, paid, carrying its transaction"
                     (is (= (:reward-id due) (:reward-id paid)))
                     (is (= :reward-status-paid (:status paid)))
                     (is (string? (:transaction-id paid)))
                     (is (some? (:paid-at paid)))
                     (is (not (contains? paid :error))))
                 legs (transactions/get-transactions config
                                                     (:account-id rewarded))
                 _ (testing "the customer's statement line is the reward"
                     (is (= 1 (count legs)))
                     (is (= :transaction-type-reward
                            (:transaction-type (first legs))))
                     (is (= "Welcome reward" (:reference (first legs))))
                     (is (= :leg-side-credit (:side (first legs))))
                     (is (= 1000 (:amount (first legs)))))
                 third-run (run config bank-id)
                 _ (testing "and a third run pays nothing"
                     (is (= {:accounts-processed 0 :accounts-failed 0}
                            (select-keys third-run
                                         [:accounts-processed
                                          :accounts-failed]))))
                 _ (is (nil? (row config bank-id unrewarded)))]))))
