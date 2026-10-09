(ns com.repldriven.queenswood.ledger-account.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.ledger-account.interface :as SUT]

    [com.repldriven.queenswood.balance-query.interface :as balances]
    [com.repldriven.queenswood.balance.interface :as balance]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.cache.interface :as cache]
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(def ^:private template
  "Test chart of accounts passed into seed!, the nine-row seed of
  components/resources/resources/ledgers/general-ledger.edn, which
  bank-bank seeds every customer bank with at provisioning time."
  [{:code :ledger-account-code-cash-at-correspondent
    :name "Cash at correspondent"}
   {:code :ledger-account-code-pending-outbound
    :name "Pending outbound payments"}
   {:code :ledger-account-code-customer-deposits-current
    :name "Customer deposits - current"}
   {:code :ledger-account-code-customer-deposits-savings
    :name "Customer deposits - savings"}
   {:code :ledger-account-code-customer-deposits-term
    :name "Customer deposits - term deposits"}
   {:code :ledger-account-code-interest-payable :name "Interest payable"}
   {:code :ledger-account-code-suspense :name "Suspense - unreconciled inbound"}
   {:code :ledger-account-code-own-funds :name "Bank own funds"}
   {:code :ledger-account-code-interest-expense :name "Interest expense"}])

(def ^:private chart-numbers
  "The chart number each role in `template` reports, as a string."
  {:ledger-account-code-cash-at-correspondent "1100"
   :ledger-account-code-pending-outbound "1200"
   :ledger-account-code-customer-deposits-current "2100"
   :ledger-account-code-customer-deposits-savings "2200"
   :ledger-account-code-customer-deposits-term "2300"
   :ledger-account-code-interest-payable "2400"
   :ledger-account-code-suspense "2500"
   :ledger-account-code-own-funds "3100"
   :ledger-account-code-interest-expense "5100"})

(def ^:private list-cap
  "The row cap `list-accounts` scans a bank's chart under."
  1000)

(defn- seed!
  "Test helper: create every template row in GBP, returning the
  created accounts or the first anomaly."
  [config bank-id]
  (reduce (fn [acc row]
            (let [result (SUT/new-account config bank-id "GBP" row)]
              (if (error/anomaly? result)
                (reduced result)
                (conj acc result))))
          []
          template))

(defn- customer-leg
  "A customer posting leg on `acc.customer1` in the current-account
  sub-ledger, defaulted to the posted default bucket a control sums."
  [overrides]
  (merge {:account-id "acc.customer1"
          :product-type :product-type-sub-ledger-current
          :balance-type :balance-type-default
          :balance-status :balance-status-posted
          :side :leg-side-credit
          :amount 1000}
         overrides))

(defn- current-deposits-control
  [config bank-id]
  (SUT/find-by-code config
                    bank-id
                    :ledger-account-code-customer-deposits-current
                    "GBP"))

;; --- Pure mapping checks ------------------------------------------------

(deftest product-type->control-code-test
  (is (= :ledger-account-code-customer-deposits-current
         (SUT/product-type->control-code :product-type-sub-ledger-current)))
  (is (= :ledger-account-code-customer-deposits-savings
         (SUT/product-type->control-code :product-type-sub-ledger-savings)))
  (is (= :ledger-account-code-customer-deposits-term
         (SUT/product-type->control-code
          :product-type-sub-ledger-term-deposit)))
  (is (= :ledger-account-code-own-funds
         (SUT/product-type->control-code :product-type-sub-ledger-own-funds)))
  (testing "non-customer product types have no control"
    (is (nil? (SUT/product-type->control-code :product-type-unknown)))))

(deftest chart-number-test
  (testing "the test chart is the nine seeded roles"
    (is (= (set (keys chart-numbers)) (set (map :code template)))))
  (testing "each role reports its chart number as a string"
    (doseq [[role number] chart-numbers]
      (is (= number (SUT/chart-number role)) (str role)))))

(deftest account-type-test
  (testing "the deposit and own-funds controls and 2400 are controls"
    (doseq [code [:ledger-account-code-customer-deposits-current
                  :ledger-account-code-customer-deposits-savings
                  :ledger-account-code-customer-deposits-term
                  :ledger-account-code-own-funds
                  :ledger-account-code-interest-payable]]
      (is (= :ledger-account-type-control (SUT/account-type code)) (str code))))
  (testing "every other role is a detail account"
    (doseq [code [:ledger-account-code-cash-at-correspondent
                  :ledger-account-code-pending-outbound
                  :ledger-account-code-suspense
                  :ledger-account-code-interest-expense]]
      (is (= :ledger-account-type-detail (SUT/account-type code)) (str code)))))

;; --- FDB-backed seed / lookup / controls -------------------------------

(deftest seed!-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-seed"]
     (testing "seeds nine ledger accounts per currency, each with a led. id"
       (nom-test> [accounts (seed! config bank-id)
                   _ (is (= 9 (count accounts)))
                   _ (is (every? #(re-find #"^led\." (:ledger-account-id %))
                                 accounts))
                   _ (is (every? #(= "GBP" (:currency %)) accounts))]))
     (testing "each seeded account but a control opens a default-posted balance"
       (nom-test> [suspense (SUT/find-by-code config
                                              bank-id
                                              :ledger-account-code-suspense
                                              "GBP")
                   bals (balances/get-balances config
                                               bank-id
                                               (:ledger-account-id suspense)
                                               "GBP")
                   _ (is (= 1 (count (:balances bals))))
                   _ (is (= :balance-type-default
                            (:balance-type (first (:balances bals)))))
                   control (current-deposits-control config bank-id)
                   stored (balances/get-balances config
                                                 bank-id
                                                 (:ledger-account-id control)
                                                 "GBP")
                   _ (is (empty? (:balances stored))
                         "a control stores no balance of its own")])))))

(deftest find-by-code-and-get-account-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-lookup"]
     (nom-test> [_ (seed! config bank-id)
                 control (current-deposits-control config bank-id)
                 _ (is (= :ledger-account-code-customer-deposits-current
                          (:code control)))
                 fetched
                 (SUT/get-account config bank-id (:ledger-account-id control))
                 _ (is (= (:ledger-account-id control)
                          (:ledger-account-id fetched)))])
     (testing "a currency the chart lacks rejects; an unknown id is nil"
       (let [result (SUT/find-by-code
                     config
                     bank-id
                     :ledger-account-code-customer-deposits-current
                     "USD")
             payload (error/payload result)]
         (is (error/anomaly? result))
         (is (= :gl/missing-currency-account (error/kind result)))
         (is (= bank-id (:bank-id payload)))
         (is (= :ledger-account-code-customer-deposits-current (:code payload)))
         (is (= "USD" (:currency payload))))
       (is (nil? (SUT/get-account config bank-id "led.nope")))))))

(deftest list-accounts-with-balances-pairs-the-chart-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-pairs"]
     (nom-test> [seeded (seed! config bank-id)
                 listed (SUT/list-accounts config bank-id)
                 paired (SUT/list-accounts-with-balances config bank-id)
                 _ (is (= (count seeded) (count paired)))
                 _ (is (= listed (mapv :account paired))
                       "the chart, in the order list-accounts gives it")
                 _ (doseq [{:keys [account balances]} paired]
                     (is (= [(:ledger-account-id account)]
                            (distinct (map :account-id balances)))
                         "each account is paired with its own balances")
                     (is (= (:balances
                             (SUT/get-balances config bank-id account))
                            balances)
                         "the same buckets the per-account read gives"))])
     (is (= [] (SUT/list-accounts-with-balances config "bnk.test-nobody"))
         "a bank with no chart pairs nothing"))))

(deftest list-accounts-caps-the-scan-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-cap"
         row (first template)]
     (nom-test> [policies (policy/get-effective-policies config
                                                         {:bank-id bank-id})
                 created
                 (reduce
                  (fn [acc n]
                    (let [result (SUT/new-account config
                                                  bank-id
                                                  (str "X" n)
                                                  row
                                                  {:policies policies})]
                      (if (error/anomaly? result) (reduced result) (inc acc))))
                  0
                  (range (inc list-cap)))
                 _ (is (= (inc list-cap) created))
                 listed (SUT/list-accounts config bank-id)
                 _ (is
                    (= list-cap (count listed))
                    "a bank with more rows than the cap lists exactly the cap")]))))

(defn- open-customer-account
  [config bank-id account-id product-type]
  (balance/new-balances config
                        bank-id
                        [{:account-id account-id
                          :product-type product-type
                          :balance-type :balance-type-default
                          :balance-status :balance-status-posted}]))

(defn- post
  "Records `legs` and applies the ones a balance row holds, as a posting
  does: a cash account's default bucket is the sum of its recorded legs."
  [config bank-id key transaction-type legs]
  (error/let-nom>
    [recorded (transactions/record-transaction config
                                               {:bank-id bank-id
                                                :idempotency-key key
                                                :transaction-type
                                                transaction-type
                                                :currency "GBP"
                                                :legs legs})
     stored (SUT/stored-legs config bank-id "GBP" (:legs recorded))]
    (balance/apply-legs config bank-id stored transaction-type)))

(defn- deposit
  [config bank-id cash-id account-id product-type amount]
  (post config
        bank-id
        (str "deposit-" account-id "-" amount)
        :transaction-type-inbound-transfer
        [{:account-id cash-id
          :balance-type :balance-type-default
          :balance-status :balance-status-posted
          :side :leg-side-debit
          :amount amount}
         {:account-id account-id
          :product-type product-type
          :balance-type :balance-type-default
          :balance-status :balance-status-posted
          :side :leg-side-credit
          :amount amount}]))

(deftest control-balance-is-its-sub-ledger-sum-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-sum"]
     (nom-test> [_ (seed! config bank-id)
                 cash (SUT/find-by-code
                       config
                       bank-id
                       :ledger-account-code-cash-at-correspondent
                       "GBP")
                 cash-id (:ledger-account-id cash)
                 _ (open-customer-account config
                                          bank-id
                                          "acc.current1"
                                          :product-type-sub-ledger-current)
                 _ (open-customer-account config
                                          bank-id
                                          "acc.current2"
                                          :product-type-sub-ledger-current)
                 _ (open-customer-account config
                                          bank-id
                                          "acc.savings1"
                                          :product-type-sub-ledger-savings)
                 _ (deposit config
                            bank-id
                            cash-id
                            "acc.current1"
                            :product-type-sub-ledger-current
                            1000)
                 _ (deposit config
                            bank-id
                            cash-id
                            "acc.current2"
                            :product-type-sub-ledger-current
                            500)
                 _ (deposit config
                            bank-id
                            cash-id
                            "acc.savings1"
                            :product-type-sub-ledger-savings
                            70)
                 control (current-deposits-control config bank-id)
                 bals (SUT/get-balances config bank-id control)
                 _ (is (= {:value 1500 :currency "GBP"} (:posted-balance bals))
                       "the current-account control sums its two accounts only")
                 _ (is (= [{:account-id (:ledger-account-id control)
                            :credit 1500
                            :debit 0}]
                          (mapv (fn [b]
                                  (select-keys b [:account-id :credit :debit]))
                                (:balances bals))))
                 listed (SUT/list-accounts-with-balances config bank-id)
                 _ (is
                    (= (:balances bals)
                       (some (fn [{:keys [account balances]}]
                               (when (= control account) balances))
                             listed))
                    "the chart pairs the control with the same summed balance")]))))

(defn- reserve
  [config bank-id pending-id account-id product-type amount]
  (post config
        bank-id
        (str "reserve-" account-id "-" amount)
        :transaction-type-outbound-transfer
        [{:account-id account-id
          :product-type product-type
          :balance-type :balance-type-default
          :balance-status :balance-status-pending-outgoing
          :side :leg-side-debit
          :amount amount}
         {:account-id pending-id
          :balance-type :balance-type-default
          :balance-status :balance-status-pending-outgoing
          :side :leg-side-credit
          :amount amount}]))

(deftest pending-outbound-mirrors-pending-outgoing-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-pending"]
     (nom-test>
       [_ (seed! config bank-id)
        cash (SUT/find-by-code config
                               bank-id
                               :ledger-account-code-cash-at-correspondent
                               "GBP")
        cash-id (:ledger-account-id cash)
        _ (open-customer-account config
                                 bank-id
                                 "acc.current1"
                                 :product-type-sub-ledger-current)
        _ (open-customer-account config
                                 bank-id
                                 "acc.savings1"
                                 :product-type-sub-ledger-savings)
        _ (deposit config
                   bank-id
                   cash-id
                   "acc.current1"
                   :product-type-sub-ledger-current
                   1000)
        _ (deposit config
                   bank-id
                   cash-id
                   "acc.savings1"
                   :product-type-sub-ledger-savings
                   1000)
        pending (SUT/find-by-code config
                                  bank-id
                                  :ledger-account-code-pending-outbound
                                  "GBP")
        _ (reserve config
                   bank-id
                   (:ledger-account-id pending)
                   "acc.current1"
                   :product-type-sub-ledger-current
                   300)
        _ (reserve config
                   bank-id
                   (:ledger-account-id pending)
                   "acc.savings1"
                   :product-type-sub-ledger-savings
                   200)
        bals (SUT/get-balances config bank-id pending)
        _
        (is
         (= [{:account-id (:ledger-account-id pending)
              :balance-status :balance-status-pending-outgoing
              :credit 500
              :debit 0}]
            (mapv (fn [b]
                    (select-keys b
                                 [:account-id :balance-status
                                  :credit :debit]))
                  (:balances bals)))
         "1200 credits what every customer reserved, across
                       product types")
        customer (customer-leg {:account-id "acc.current1"
                                :balance-status :balance-status-pending-outgoing
                                :side :leg-side-debit})
        claim {:account-id (:ledger-account-id pending)
               :balance-type :balance-type-default
               :balance-status :balance-status-pending-outgoing
               :side :leg-side-credit
               :amount 1000}
        stored (SUT/stored-legs config bank-id "GBP" [customer claim])
        _ (is (= [customer] stored) "the 1200 leg is not written to a balance")
        unlisted (SUT/stored-legs config bank-id "EUR" [customer])
        _ (is (= [customer] unlisted)
              "a currency with no journal accounts keeps every leg")]))))

(defn- inbound
  [config bank-id cash-id account-id amount]
  (transactions/record-transaction
   config
   {:bank-id bank-id
    :idempotency-key (str "in-" account-id "-" amount)
    :transaction-type :transaction-type-inbound-transfer
    :currency "GBP"
    :legs [{:account-id cash-id
            :balance-type :balance-type-default
            :balance-status :balance-status-posted
            :side :leg-side-debit
            :amount amount}
           {:account-id account-id
            :balance-type :balance-type-default
            :balance-status :balance-status-posted
            :side :leg-side-credit
            :amount amount}]}))

(deftest cash-at-correspondent-sums-its-legs-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-cash"]
     (nom-test> [_ (seed! config bank-id)
                 cash (SUT/find-by-code
                       config
                       bank-id
                       :ledger-account-code-cash-at-correspondent
                       "GBP")
                 cash-id (:ledger-account-id cash)
                 first-in (inbound config bank-id cash-id "acc.one" 1000)
                 _ (inbound config bank-id cash-id "acc.two" 250)
                 bals (SUT/get-balances config bank-id cash)
                 _ (is (= [{:account-id cash-id
                            :balance-status :balance-status-posted
                            :credit 0
                            :debit 1250}]
                          (mapv (fn [b]
                                  (select-keys b
                                               [:account-id :balance-status
                                                :credit :debit]))
                                (:balances bals)))
                       "1100 is the sum of the legs recorded against it")
                 stored (SUT/stored-legs config bank-id "GBP" (:legs first-in))
                 _ (is (= ["acc.one"] (mapv :account-id stored))
                       "the 1100 leg is left out of the balance writes")]))))

(deftest a-bucket-takes-its-accounts-product-type-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-inherit"]
     (nom-test> [_ (seed! config bank-id)
                 _ (open-customer-account config
                                          bank-id
                                          "acc.current1"
                                          :product-type-sub-ledger-current)
                 _ (balance/apply-legs
                    config
                    bank-id
                    [{:account-id "acc.current1"
                      :balance-type :balance-type-default
                      :balance-status :balance-status-pending-incoming
                      :side :leg-side-credit
                      :amount 40
                      :currency "GBP"}
                     {:account-id "acc.current1"
                      :balance-type :balance-type-default
                      :balance-status :balance-status-pending-incoming
                      :side :leg-side-debit
                      :amount 40
                      :currency "GBP"}]
                    :transaction-type-inbound-transfer)
                 opened (balances/get-balance config
                                              bank-id
                                              "acc.current1"
                                              :balance-type-default
                                              :balance-status-pending-incoming)
                 _
                 (is
                  (= :product-type-sub-ledger-current (:product-type opened))
                  "an untagged leg opens the bucket under its account's type")]))))

(deftest ensure-controls-returns-legs-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-ensure"]
     (nom-test> [_ (seed! config bank-id)
                 legs [(customer-leg {})
                       (customer-leg {:side :leg-side-debit
                                      :account-id "acc.customer2"})]
                 checked (SUT/ensure-controls config bank-id "GBP" legs)
                 _ (is (= legs checked) "no leg is added for the control")])
     (testing "only a posted default or accrued customer leg is checked"
       (let [pending (customer-leg {:balance-status
                                    :balance-status-pending-outgoing})
             accrued (customer-leg {:balance-type
                                    :balance-type-interest-accrued})
             gl-leg {:account-id "led.something"
                     :balance-type :balance-type-default
                     :balance-status :balance-status-posted
                     :side :leg-side-debit
                     :amount 1000}]
         (is (= [pending gl-leg]
                (SUT/ensure-controls config bank-id "USD" [pending gl-leg]))
             "in a currency with no controls, unchecked legs pass")
         (is (= :gl/missing-currency-account
                (error/kind
                 (SUT/ensure-controls config bank-id "USD" [accrued])))
             "an accrued leg needs the currency's 2400")
         (is (= [accrued] (SUT/ensure-controls config bank-id "GBP" [accrued]))
             "and passes where it has one"))))))

(deftest ensure-controls-unseeded-control-rejects-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-unseeded-control"
         seeded (seed! config bank-id)
         result (SUT/ensure-controls config bank-id "USD" [(customer-leg {})])
         payload (error/payload result)]
     (is (not (error/anomaly? seeded)))
     (is (error/anomaly? result)
         "a posted default leg whose control is unseeded rejects")
     (is (= :gl/missing-currency-account (error/kind result)))
     (is (= :ledger-account-code-customer-deposits-current (:code payload)))
     (is (= "USD" (:currency payload))))))

;; --- Close lifecycle -----------------------------------------------------

(defn- suspense-account
  [config bank-id]
  (SUT/find-by-code config bank-id :ledger-account-code-suspense "GBP"))

(deftest close-account-zero-balance-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-close-zero"]
     (nom-test> [_ (seed! config bank-id)
                 account (suspense-account config bank-id)
                 closed
                 (SUT/close-account config bank-id (:ledger-account-id account))
                 _ (is (= :ledger-account-status-closed (:status closed)))
                 fetched
                 (SUT/get-account config bank-id (:ledger-account-id account))
                 _ (is (= :ledger-account-status-closed (:status fetched))
                       "closed status round-trips through the store")]))))

(deftest close-account-already-closed-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-close-double"
         seeded (seed! config bank-id)
         account (suspense-account config bank-id)
         first-close
         (SUT/close-account config bank-id (:ledger-account-id account))
         result (SUT/close-account config bank-id (:ledger-account-id account))]
     (is (not (error/anomaly? seeded)))
     (is (not (error/anomaly? first-close)))
     (is (error/anomaly? result))
     (is (= :ledger-account/invalid-status (error/kind result))))))

(deftest a-cached-id-still-refuses-a-closed-account-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [ledger-cache (cache/create 60000)
         config (assoc (fdb-config sys) :caches {:ledger-account ledger-cache})
         bank-id "bnk.test-cached-close"]
     (nom-test> [_ (seed! config bank-id)
                 found (suspense-account config bank-id)
                 _ (is (= (:ledger-account-id found)
                          (cache/lookup ledger-cache
                                        [bank-id :ledger-account-code-suspense
                                         "GBP"]
                                        (constantly nil)))
                       "the code's id is cached")
                 again (suspense-account config bank-id)
                 _ (is (= (:ledger-account-id found)
                          (:ledger-account-id again)))
                 _
                 (SUT/close-account config bank-id (:ledger-account-id found))])
     (testing "the account behind a cached id is read, so a close is refused"
       (is (= :ledger-account/closed
              (error/kind (suspense-account config bank-id))))))))

(deftest a-prefetched-account-is-read-as-it-stands-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config
         (assoc (fdb-config sys) :caches {:ledger-account (cache/create 60000)})
         bank-id "bnk.test-prefetch"]
     (nom-test> [_ (seed! config bank-id)
                 _ (SUT/find-by-code config
                                     bank-id
                                     :ledger-account-code-cash-at-correspondent
                                     "GBP")])
     (testing "a prefetched account closed in the same transaction reads closed"
       (let [result
             (fdb/transact
              config
              (fn [txn]
                (let [_ (SUT/prefetch txn
                                      bank-id
                                      "GBP"
                                      [:product-type-sub-ledger-current])
                      cash (SUT/find-by-code
                            txn
                            bank-id
                            :ledger-account-code-cash-at-correspondent
                            "GBP")]
                  (SUT/close-account txn bank-id (:ledger-account-id cash))
                  (error/kind (SUT/find-by-code
                               txn
                               bank-id
                               :ledger-account-code-cash-at-correspondent
                               "GBP")))))]
         (is (= :ledger-account/closed result)))))))

(deftest closed-control-rejects-a-posting-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-close-control"
         seeded (seed! config bank-id)
         control (current-deposits-control config bank-id)
         closed (SUT/close-account config bank-id (:ledger-account-id control))
         result (SUT/ensure-controls config bank-id "GBP" [(customer-leg {})])]
     (is (not (error/anomaly? seeded)))
     (is (not (error/anomaly? closed)))
     (is (error/anomaly? result))
     (is (= :ledger-account/closed (error/kind result))))))

(deftest close-account-policy-denied-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-close-denied"
         deny-policy {:status :policy-status-active
                      :capabilities [{:effect :effect-deny
                                      :reason "Test deny"
                                      :kind {:ledger-account
                                             {:action
                                              :ledger-account-action-close}}}]}
         seeded (seed! config bank-id)
         account (suspense-account config bank-id)
         result (SUT/close-account config
                                   bank-id
                                   (:ledger-account-id account)
                                   {:policies [deny-policy]})
         fetched (SUT/get-account config bank-id (:ledger-account-id account))]
     (is (not (error/anomaly? seeded)))
     (is (error/anomaly? result))
     (is (error/unauthorized? result))
     (is (not= :ledger-account-status-closed (:status fetched))
         "a denied close leaves the account open"))))
