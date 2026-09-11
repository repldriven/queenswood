(ns ^:eftest/synchronized
    com.repldriven.queenswood.cash-account-query.interface-test
  "The read surface against a real record store, which is the only
  place its behaviour shows: which index answers a lookup, whether a
  lookup is scoped by bank, and what the merged scan pairs.

  Rows are written straight into the two stores this brick reads, in
  the shape the write bricks leave them. Reaching the same rows by
  driving the write bricks is a scenario's job."
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.cash-account-query.interface :as SUT]

    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:cash-account-query/application-test.yml")

(def ^:private sort-code "040404")

;; must match cash-account.store/store-name and balance.store/store-name
;; — the two stores this brick reads
(def ^:private accounts-store "cash-accounts")
(def ^:private balances-store "balances")

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(defn- account
  "An opened current account as `cash-account.store` leaves it. The
  bban is the unique index, so `account-number` is unique per test."
  [bank-id account-id party-id version-id account-number]
  (let [now (utility/now)]
    {:bank-id bank-id
     :account-id account-id
     :account-type :account-type-business
     :party-id party-id
     :product-id "prd.query"
     :version-id version-id
     :product-type :product-type-sub-ledger-current
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
  "A bucket as `balance.store` opens it, before any leg."
  [bank-id account-id balance-status]
  (let [now (utility/now)]
    {:bank-id bank-id
     :account-id account-id
     :product-type :product-type-sub-ledger-current
     :balance-type :balance-type-default
     :balance-status balance-status
     :currency "GBP"
     :credit 0
     :debit 0
     :credit-carry 0
     :created-at now
     :updated-at now}))

(defn- seed
  "Write `accounts` and `balances` in one transaction."
  [config accounts balances]
  (fdb/transact config
                (fn [txn]
                  (let [acc-store (fdb/open txn accounts-store)
                        bal-store (fdb/open txn balances-store)]
                    (doseq [a accounts]
                      (fdb/save-record acc-store (schema/CashAccount->java a)))
                    (doseq [b balances]
                      (fdb/save-record bal-store (schema/Balance->java b)))
                    nil))
                :test/seed
                "Failed to seed rows"))

(deftest get-account-by-bban-resolves-across-banks-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         account-a (account "bnk_bban_a" "acc.a" "pty.a" "prv.1" "10000001")
         account-b (account "bnk_bban_b" "acc.b" "pty.b" "prv.1" "10000002")]
     (nom-test> [_ (seed config [account-a account-b] [])
                 _ (testing "the unique index answers with the account"
                     (nom-test> [found (SUT/get-account-by-bban config
                                                                (:bban
                                                                 account-a))
                                 _ (is (= "acc.a" (:account-id found)))
                                 _ (is (= "bnk_bban_a" (:bank-id found)))]))
                 _ (testing "and takes no bank, so it reaches another bank's"
                     (nom-test> [found (SUT/get-account-by-bban config
                                                                (:bban
                                                                 account-b))
                                 _ (is (= "acc.b" (:account-id found)))
                                 _ (is (= "bnk_bban_b" (:bank-id found)))]))
                 _ (testing "a bban nobody holds is nil, not a rejection"
                     (nom-test> [found (SUT/get-account-by-bban
                                        config
                                        "04040499999999")
                                 _ (is (nil? found))]))]))))

(deftest find-accounts-by-party-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk_party_lookup"
         other-bank-id "bnk_party_lookup_other"]
     (nom-test> [_
                 (seed
                  config
                  [(account bank-id "acc.first" "pty.holder" "prv.1" "20000001")
                   (account bank-id
                            "acc.second" "pty.holder"
                            "prv.1" "20000002")
                   (account bank-id "acc.other" "pty.other" "prv.1" "20000003")
                   (account other-bank-id
                            "acc.elsewhere" "pty.holder"
                            "prv.1" "20000004")]
                  [])
                 found (SUT/find-accounts-by-party config bank-id "pty.holder")
                 _ (testing
                     "every account that party holds at this bank, no other's"
                     (is (= #{"acc.first" "acc.second"}
                            (set (map :account-id found)))))
                 none (SUT/find-accounts-by-party config bank-id "pty.nobody")
                 _ (is (= [] none))]))))

(deftest count-by-version-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk_version_count"]
     (nom-test> [_ (seed
                    config
                    [(account bank-id "acc.1" "pty.1" "prv.first" "30000001")
                     (account bank-id "acc.2" "pty.1" "prv.first" "30000002")
                     (account bank-id "acc.3" "pty.1" "prv.second" "30000003")
                     (account "bnk_version_count_other"
                              "acc.4" "pty.1"
                              "prv.first" "30000004")]
                    [])
                 on-first (SUT/count-by-version config bank-id "prv.first")
                 on-second (SUT/count-by-version config bank-id "prv.second")
                 on-none (SUT/count-by-version config bank-id "prv.unused")
                 _ (testing "each version counts its own accounts at this bank"
                     (is (= 2 on-first))
                     (is (= 1 on-second))
                     (is (= 0 on-none)))]))))

(def ^:private real-merge-scan fdb/merge-scan)

(defn- paged-merge-scan
  "The query brick fixes the merged scan's page limits, so rewriting
  them on the way through is the only way to make the scan refill —
  and a group that straddles a page boundary is exactly what a refill
  has to keep whole."
  [size]
  (fn [config opts f init]
    (real-merge-scan config
                     (-> opts
                         (assoc-in [:left :limit] size)
                         (assoc-in [:right :limit] size))
                     f
                     init)))

(defn- account-balance-counts
  "`{account-id balance-count}` for one bank, off the merged scan."
  [config bank-id]
  (SUT/reduce-accounts-with-balances config
                                     bank-id
                                     (fn [acc {:keys [account balances]}]
                                       (assoc acc
                                              (:account-id account)
                                              (count balances)))
                                     {}))

(deftest reduce-accounts-with-balances-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk_merged_scan"
         other-bank-id "bnk_merged_scan_other"]
     (nom-test> [_
                 (seed
                  config
                  [(account bank-id "acc.one" "pty.1" "prv.1" "40000001")
                   (account bank-id "acc.two" "pty.1" "prv.1" "40000002")
                   (account bank-id "acc.none" "pty.1" "prv.1" "40000003")
                   (account other-bank-id
                            "acc.other" "pty.2"
                            "prv.1" "40000004")]
                  [(balance bank-id "acc.one" :balance-status-posted)
                   (balance bank-id "acc.two" :balance-status-posted)
                   (balance bank-id "acc.two" :balance-status-pending-outgoing)
                   (balance other-bank-id "acc.other" :balance-status-posted)])
                 expected (account-balance-counts config bank-id)
                 _ (testing "every account the bank holds is visited once"
                     (is (= #{"acc.one" "acc.two" "acc.none"}
                            (set (keys expected)))))
                 _ (testing "paired with every bucket it has, or with none"
                     (is (= 1 (get expected "acc.one")))
                     (is (= 2 (get expected "acc.two")))
                     (is (= 0 (get expected "acc.none"))))
                 _ (testing "and another bank's rows are not read"
                     (is (not (contains? expected "acc.other"))))
                 _ (doseq [size [1 2 100]]
                     (testing (str "page size " size " reduces to the same")
                       (with-redefs [fdb/merge-scan (paged-merge-scan size)]
                         (nom-test> [paged (account-balance-counts config
                                                                   bank-id)
                                     _ (is (= expected paged))]))))]))))

(deftest get-accounts-paging-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk_cursor_paging"
         ids (mapv (fn [n] (str "acc.page." n)) (range 5))]
     (nom-test> [_ (seed
                    config
                    (map-indexed
                     (fn [n id]
                       (account bank-id id "pty.1" "prv.1" (str "5000000" n)))
                     ids)
                    [])
                 _ (testing "paging two at a time walks every account once"
                     (let [walked (loop [cursor nil
                                         seen []]
                                    (let [page (SUT/get-accounts
                                                config
                                                bank-id
                                                (cond-> {:limit 2}
                                                        cursor
                                                        (assoc :after
                                                               cursor)))
                                          ids (map :account-id (:accounts page))
                                          seen (into seen ids)]
                                      (if-let [next-cursor (:after page)]
                                        (recur next-cursor seen)
                                        seen)))]
                       (is (= 5 (count walked)))
                       (is (= (set ids) (set walked)))))]))))
