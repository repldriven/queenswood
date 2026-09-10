(ns ^:eftest/synchronized
    com.repldriven.queenswood.cash-account-query.interface-test
  "The read surface against a real record store, which is the only
  place its behaviour shows: which index answers a lookup, whether a
  lookup is scoped by bank, and what the merged scan pairs.

  Accounts are seeded through the write bricks' own interfaces rather
  than written straight to the store, so every row here has the shape
  an opened account really has — including its balances."
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.cash-account-query.interface :as SUT]

    [com.repldriven.queenswood.balance-query.interface :as balance-query]
    [com.repldriven.queenswood.cash-account-product.interface :as products]
    [com.repldriven.queenswood.cash-account.interface :as cash-accounts]
    [com.repldriven.queenswood.party.interface :as parties]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(def ^:private config-file "classpath:cash-account-query/application-test.yml")

(def ^:private sort-code "040404")

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(def ^:private current-template
  {:template-id "tpl.query-current"
   :name "Query Current"
   :product-type :product-type-sub-ledger-current
   :balance-sheet-side :balance-sheet-side-liability
   :iso-cash-account-type :iso-cash-account-type-cacc
   :allowed-currencies ["GBP"]
   :allowed-payment-address-schemes [:payment-address-scheme-scan]
   :balance-products [{:balance-type :balance-type-default
                       :balance-status :balance-status-posted}]})

(def ^:private bucketless-template
  "A template declaring an empty bucket list rather than none at all.
  `opening-balances` falls back to a default posted bucket only when
  the version declares nothing, so an account opened on this one is
  written with no balance rows — the merged scan's absent-right side."
  (assoc current-template
         :template-id "tpl.query-bucketless"
         :name "Query Bucketless"
         :balance-products []))

(defn- published-version
  "A published v1 of a new product on `template-id`."
  [config bank-id template-id product-name]
  (let-nom>
    [draft (products/new-product config
                                 bank-id
                                 {:name product-name
                                  :template-id template-id
                                  :currency "GBP"
                                  :effective-from 20089})
     published (products/publish config
                                 bank-id
                                 (:product-id draft)
                                 (:version-id draft))]
    published))

(defn- active-party
  [config bank-id display-name]
  (let-nom>
    [party (parties/new-party config
                              {:bank-id bank-id
                               :type :party-type-organization
                               :display-name display-name})
     _ (parties/seed-active-party config bank-id (:party-id party))]
    party))

(defn- opened-account
  [config bank-id party version account-name]
  (let-nom>
    [account (cash-accounts/new-account config
                                        {:bank-id bank-id
                                         :party-id (:party-id party)
                                         :product-id (:product-id version)
                                         :currency "GBP"
                                         :sort-code sort-code
                                         :name account-name})
     _ (cash-accounts/seed-opened-account config
                                          bank-id
                                          (:account-id account))]
    account))

(deftest get-account-by-bban-resolves-across-banks-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)]
     (nom-test> [_ (products/new-template config current-template)
                 version-a (published-version config
                                              "bnk_bban_a"
                                              "tpl.query-current"
                                              "A Current")
                 version-b (published-version config
                                              "bnk_bban_b"
                                              "tpl.query-current"
                                              "B Current")
                 party-a (active-party config "bnk_bban_a" "Holder A")
                 party-b (active-party config "bnk_bban_b" "Holder B")
                 account-a
                 (opened-account config "bnk_bban_a" party-a version-a "A")
                 account-b
                 (opened-account config "bnk_bban_b" party-b version-b "B")
                 _ (testing "the unique index answers with the account"
                     (nom-test> [found (SUT/get-account-by-bban config
                                                                (:bban
                                                                 account-a))
                                 _ (is (= (:account-id account-a)
                                          (:account-id found)))
                                 _ (is (= "bnk_bban_a" (:bank-id found)))]))
                 _ (testing "and takes no bank, so it reaches another bank's"
                     (nom-test> [found (SUT/get-account-by-bban config
                                                                (:bban
                                                                 account-b))
                                 _ (is (= (:account-id account-b)
                                          (:account-id found)))
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
         bank-id "bnk_party_lookup"]
     (nom-test> [_ (products/new-template config current-template)
                 version (published-version config
                                            bank-id
                                            "tpl.query-current"
                                            "Current")
                 holder (active-party config bank-id "Holder")
                 other (active-party config bank-id "Other Holder")
                 first-account
                 (opened-account config bank-id holder version "First")
                 second-account
                 (opened-account config bank-id holder version "Second")
                 _ (opened-account config bank-id other version "Other")
                 found
                 (SUT/find-accounts-by-party config bank-id (:party-id holder))
                 _ (testing "every account that party holds, and no other's"
                     (is (= #{(:account-id first-account)
                              (:account-id second-account)}
                            (set (map :account-id found)))))
                 none (SUT/find-accounts-by-party config bank-id "pty.nobody")
                 _ (is (= [] none))]))))

(deftest count-by-version-test
  (with-test-system
   [sys config-file]
   (let [config (fdb-config sys)
         bank-id "bnk_version_count"]
     (nom-test> [_ (products/new-template config current-template)
                 first-version (published-version config
                                                  bank-id
                                                  "tpl.query-current"
                                                  "First Product")
                 second-version (published-version config
                                                   bank-id
                                                   "tpl.query-current"
                                                   "Second Product")
                 party (active-party config bank-id "Holder")
                 _
                 (opened-account config bank-id party first-version "On first")
                 _ (testing "an open on one version moves that version's count"
                     (nom-test> [first-count (SUT/count-by-version
                                              config
                                              bank-id
                                              (:version-id first-version))
                                 _ (is (= 1 first-count))
                                 second-count (SUT/count-by-version
                                               config
                                               bank-id
                                               (:version-id second-version))
                                 _ (is (= 0 second-count))]))
                 _ (opened-account config
                                   bank-id
                                   party
                                   second-version
                                   "On second")
                 _ (testing "and not another's"
                     (nom-test> [first-count (SUT/count-by-version
                                              config
                                              bank-id
                                              (:version-id first-version))
                                 _ (is (= 1 first-count))
                                 second-count (SUT/count-by-version
                                               config
                                               bank-id
                                               (:version-id second-version))
                                 _ (is (= 1 second-count))]))]))))

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
     (nom-test> [_ (products/new-template config current-template)
                 _ (products/new-template config bucketless-template)
                 version (published-version config
                                            bank-id
                                            "tpl.query-current"
                                            "Current")
                 bucketless (published-version config
                                               bank-id
                                               "tpl.query-bucketless"
                                               "Bucketless")
                 other-version (published-version config
                                                  other-bank-id
                                                  "tpl.query-current"
                                                  "Other Current")
                 party (active-party config bank-id "Holder")
                 other-party (active-party config other-bank-id "Other Holder")
                 first-account
                 (opened-account config bank-id party version "First")
                 second-account
                 (opened-account config bank-id party version "Second")
                 empty-account
                 (opened-account config bank-id party bucketless "No buckets")
                 other-account (opened-account config
                                               other-bank-id
                                               other-party
                                               other-version
                                               "Other")
                 expected (account-balance-counts config bank-id)
                 _ (testing "every account the bank holds is visited once"
                     (is (= #{(:account-id first-account)
                              (:account-id second-account)
                              (:account-id empty-account)}
                            (set (keys expected)))))
                 _ (testing "an account with no balances arrives with none"
                     (is (= 0 (get expected (:account-id empty-account))))
                     (is (= 1 (get expected (:account-id first-account)))))
                 _ (testing "and another bank's rows are not read"
                     (is (not (contains? expected
                                         (:account-id other-account)))))
                 stored (balance-query/list-balances config
                                                     bank-id
                                                     (:account-id
                                                      empty-account))
                 _ (is (empty? stored)
                       "the account really has no balance rows to pair")
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
         bank-id "bnk_cursor_paging"]
     (nom-test> [_ (products/new-template config current-template)
                 version (published-version config
                                            bank-id
                                            "tpl.query-current"
                                            "Current")
                 party (active-party config bank-id "Holder")
                 opened (reduce (fn [acc n]
                                  (let [account (opened-account config
                                                                bank-id
                                                                party
                                                                version
                                                                (str "Account "
                                                                     n))]
                                    (if (map? account)
                                      (conj acc (:account-id account))
                                      (reduced account))))
                                []
                                (range 5))
                 _ (is (= 5 (count opened)))
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
                       (is (= (set opened) (set walked)))))]))))
