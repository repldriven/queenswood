(ns com.repldriven.queenswood.ledger-account.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.ledger-account.interface :as SUT]

    [com.repldriven.queenswood.balance-query.interface :as balances]
    [com.repldriven.queenswood.balance.interface :as balance-writes]
    [com.repldriven.queenswood.policy.interface :as policy]

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
  [{:gl-account-code :gl-account-code-cash-at-correspondent
    :name "Cash at correspondent"
    :gl-account-type :gl-account-type-asset
    :gl-account-class :gl-account-class-detail
    :required :required-mandatory}
   {:gl-account-code :gl-account-code-pending-outbound
    :name "Pending outbound payments"
    :gl-account-type :gl-account-type-asset
    :gl-account-class :gl-account-class-detail
    :required :required-mandatory}
   {:gl-account-code :gl-account-code-customer-deposits-current
    :name "Customer deposits - current"
    :gl-account-type :gl-account-type-liability
    :gl-account-class :gl-account-class-control
    :required :required-mandatory}
   {:gl-account-code :gl-account-code-customer-deposits-savings
    :name "Customer deposits - savings"
    :gl-account-type :gl-account-type-liability
    :gl-account-class :gl-account-class-control
    :required :required-mandatory}
   {:gl-account-code :gl-account-code-customer-deposits-term
    :name "Customer deposits - term deposits"
    :gl-account-type :gl-account-type-liability
    :gl-account-class :gl-account-class-control
    :required :required-mandatory}
   {:gl-account-code :gl-account-code-interest-payable
    :name "Interest payable"
    :gl-account-type :gl-account-type-liability
    :gl-account-class :gl-account-class-control
    :required :required-mandatory}
   {:gl-account-code :gl-account-code-suspense
    :name "Suspense - unreconciled inbound"
    :gl-account-type :gl-account-type-liability
    :gl-account-class :gl-account-class-detail
    :required :required-mandatory}
   {:gl-account-code :gl-account-code-own-funds
    :name "Bank own funds"
    :gl-account-type :gl-account-type-equity
    :gl-account-class :gl-account-class-control
    :required :required-mandatory}
   {:gl-account-code :gl-account-code-interest-expense
    :name "Interest expense"
    :gl-account-type :gl-account-type-expense
    :gl-account-class :gl-account-class-detail
    :required :required-mandatory}])

(def ^:private chart-numbers
  "The chart number each role in `template` reports, as a string."
  {:gl-account-code-cash-at-correspondent "1100"
   :gl-account-code-pending-outbound "1200"
   :gl-account-code-customer-deposits-current "2100"
   :gl-account-code-customer-deposits-savings "2200"
   :gl-account-code-customer-deposits-term "2300"
   :gl-account-code-interest-payable "2400"
   :gl-account-code-suspense "2500"
   :gl-account-code-own-funds "3100"
   :gl-account-code-interest-expense "5100"})

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
  sub-ledger, defaulted to the posted default bucket that fans out."
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
                    :gl-account-code-customer-deposits-current
                    "GBP"))

;; --- Pure mapping checks ------------------------------------------------

(deftest product-type->control-code-test
  (is (= :gl-account-code-customer-deposits-current
         (SUT/product-type->control-code :product-type-sub-ledger-current)))
  (is (= :gl-account-code-customer-deposits-savings
         (SUT/product-type->control-code :product-type-sub-ledger-savings)))
  (is (= :gl-account-code-customer-deposits-term
         (SUT/product-type->control-code
          :product-type-sub-ledger-term-deposit)))
  (is (= :gl-account-code-own-funds
         (SUT/product-type->control-code :product-type-sub-ledger-own-funds)))
  (testing "non-customer product types have no control"
    (is (nil? (SUT/product-type->control-code :product-type-unknown)))))

(deftest gl-account-code->gl-code-test
  (testing "the test chart is the nine seeded roles"
    (is (= (set (keys chart-numbers)) (set (map :gl-account-code template)))))
  (testing "each role reports its chart number as a string"
    (doseq [[role number] chart-numbers]
      (is (= number (SUT/gl-account-code->gl-code role)) (str role)))))

;; --- FDB-backed seed / lookup / add-control-legs
;; -----------------------------

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
     (testing "each seeded account opens a default-posted balance"
       (nom-test> [control (current-deposits-control config bank-id)
                   bals (balances/get-balances config
                                               bank-id
                                               (:ledger-account-id control))
                   _ (is (= 1 (count (:balances bals))))
                   _ (is (= :balance-type-default
                            (:balance-type (first (:balances bals)))))])))))

(deftest find-by-code-and-get-account-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-lookup"]
     (nom-test> [_ (seed! config bank-id)
                 control (current-deposits-control config bank-id)
                 _ (is (= :gl-account-code-customer-deposits-current
                          (:gl-account-code control)))
                 _ (is (= :gl-account-class-control
                          (:gl-account-class control)))
                 fetched
                 (SUT/get-account config bank-id (:ledger-account-id control))
                 _ (is (= (:ledger-account-id control)
                          (:ledger-account-id fetched)))])
     (testing "a currency the chart lacks rejects; an unknown id is nil"
       (let [result (SUT/find-by-code config
                                      bank-id
                                      :gl-account-code-customer-deposits-current
                                      "USD")
             payload (error/payload result)]
         (is (error/anomaly? result))
         (is (= :gl/missing-currency-account (error/kind result)))
         (is (= bank-id (:bank-id payload)))
         (is (= :gl-account-code-customer-deposits-current
                (:gl-account-code payload)))
         (is (= "USD" (:currency payload))))
       (is (nil? (SUT/get-account config bank-id "led.nope")))))))

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
                  (fn [acc _]
                    (let [result (SUT/new-account config
                                                  bank-id
                                                  "GBP"
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

(deftest add-control-legs-fans-out-posted-default-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-expand"]
     (nom-test> [_ (seed! config bank-id)
                 control (current-deposits-control config bank-id)
                 leg (customer-leg {})
                 expanded (SUT/add-control-legs config bank-id "GBP" [leg])
                 _ (is (= 2 (count expanded)))
                 _ (is (= leg (first expanded)))
                 mirror (second expanded)
                 _ (is (= (:ledger-account-id control) (:account-id mirror)))
                 _ (is (= (:side leg) (:side mirror))
                       "the mirror posts on the customer leg's side")
                 _ (is (= :leg-side-credit (:side mirror)))
                 _ (is (= (:amount leg) (:amount mirror)))
                 _ (is (true? (:control mirror)))
                 _ (is (= :balance-type-default (:balance-type mirror)))
                 _ (is (= :balance-status-posted (:balance-status mirror)))])
     (testing "a debit customer leg mirrors on the debit side"
       (nom-test> [leg (customer-leg {:side :leg-side-debit})
                   expanded (SUT/add-control-legs config bank-id "GBP" [leg])
                   _ (is (= 2 (count expanded)))
                   _ (is (= :leg-side-debit (:side (second expanded))))])))))

(deftest add-control-legs-skips-non-fanning-legs-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-no-fan"]
     (nom-test> [_ (seed! config bank-id)
                 control (current-deposits-control config bank-id)
                 control-id (:ledger-account-id control)
                 pending (customer-leg {:balance-status
                                        :balance-status-pending-outgoing
                                        :side :leg-side-debit})
                 accrued (customer-leg {:balance-type
                                        :balance-type-interest-accrued})
                 gl-leg {:account-id "led.something"
                         :balance-type :balance-type-default
                         :balance-status :balance-status-posted
                         :side :leg-side-debit
                         :amount 1000}
                 from-pending
                 (SUT/add-control-legs config bank-id "GBP" [pending])
                 from-accrued
                 (SUT/add-control-legs config bank-id "GBP" [accrued])
                 from-gl (SUT/add-control-legs config bank-id "GBP" [gl-leg])
                 _ (is (= [pending] from-pending)
                       "a pending-outgoing default leg does not fan out")
                 _ (is (= [accrued] from-accrued)
                       "an interest-accrued leg does not fan out")
                 _ (is (= [gl-leg] from-gl)
                       "a leg with no customer product type passes through")
                 bals (balances/get-balances config bank-id control-id)
                 _
                 (is
                  (= 1 (count (:balances bals)))
                  "no control gains a bucket from a leg that does not fan out")]))))

(deftest add-control-legs-unseeded-control-rejects-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-unseeded-control"
         seeded (seed! config bank-id)
         result (SUT/add-control-legs config bank-id "USD" [(customer-leg {})])
         payload (error/payload result)]
     (is (not (error/anomaly? seeded)))
     (is (error/anomaly? result)
         "a posted default leg whose control is unseeded rejects")
     (is (= :gl/missing-currency-account (error/kind result)))
     (is (= :gl-account-code-customer-deposits-current
            (:gl-account-code payload)))
     (is (= "USD" (:currency payload))))))

;; --- Close lifecycle -----------------------------------------------------

(defn- suspense-account
  [config bank-id]
  (SUT/find-by-code config bank-id :gl-account-code-suspense "GBP"))

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

(deftest close-account-non-zero-balance-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-close-nonzero"
         seeded (seed! config bank-id)
         account (suspense-account config bank-id)
         posted (balance-writes/apply-legs
                 config
                 bank-id
                 [{:account-id (:ledger-account-id account)
                   :balance-type :balance-type-default
                   :balance-status :balance-status-posted
                   :side :leg-side-debit
                   :amount 500}]
                 :transaction-type-fee)
         result (SUT/close-account config bank-id (:ledger-account-id account))
         fetched (SUT/get-account config bank-id (:ledger-account-id account))]
     (is (not (error/anomaly? seeded)))
     (is (not (error/anomaly? posted)))
     (is (error/anomaly? result))
     (is (= :gl/non-zero-on-close (error/kind result)))
     (is (not= :ledger-account-status-closed (:status fetched))
         "a rejected close leaves the account open"))))

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

(deftest closed-control-rejects-fan-out-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-close-control"
         seeded (seed! config bank-id)
         control (current-deposits-control config bank-id)
         closed (SUT/close-account config bank-id (:ledger-account-id control))
         result (SUT/add-control-legs config bank-id "GBP" [(customer-leg {})])]
     (is (not (error/anomaly? seeded)))
     (is (not (error/anomaly? closed)))
     (is (error/anomaly? result))
     (is (= :ledger-account/closed (error/kind result))))))

(deftest close-account-policy-denied-test
  (with-test-system
   [sys "classpath:ledger-account/application-test.yml"]
   (let [config (fdb-config sys)
         bank-id "bnk.test-close-denied"
         deny-policy {:enabled true
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
