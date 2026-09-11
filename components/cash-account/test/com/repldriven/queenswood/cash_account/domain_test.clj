(ns com.repldriven.queenswood.cash-account.domain-test
  "Pure-function tests for the opening guards and for the
  close/suspend/resume/rotate-address source-state guards. No FDB, no
  processor — this pins the lifecycle-transition convention
  (docs/recipes/code/lifecycle-transitions.md): reject before any
  capability/limit check when the account isn't in a valid source
  state. The opening guards — the unpublished version, the currency
  and the party status — are the rejection paths the open-cash-account
  command surfaces."
  (:require
    [com.repldriven.queenswood.cash-account.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(defn- account
  [status]
  {:account-id "acc.test"
   :account-type :account-type-personal
   :account-status status
   :currency "GBP"})

(defn- policy-allowing
  [& actions]
  {:enabled true
   :capabilities (mapv (fn [action]
                         {:effect :effect-allow
                          :kind {:cash-account {:action action}}})
                       actions)})

(def ^:private open-as-of 20468)

(def ^:private open-data
  {:bank-id "bnk.test"
   :party-id "pty.test"
   :product-id "prd.001"
   :name "Test Account"
   :sort-code "040004"})

(defn- party-with
  [status]
  {:party-id "pty.test" :type :party-type-person :status status})

(defn- version-allowing
  [allowed-currencies]
  {:version-id "prv.001"
   :product-type :product-type-sub-ledger-current
   :allowed-currencies allowed-currencies
   :allowed-payment-address-schemes [:payment-address-scheme-scan]})

(defn- open-account-with
  [currency product-version party]
  (SUT/open-account (assoc open-data :currency currency)
                    product-version
                    open-as-of
                    party
                    (constantly "12345678")
                    nil
                    []))

(deftest open-account-no-published-version-test
  (testing
    "a product with no version effective on the day is rejected as
           unpublished, naming the product and the day"
    (let [result
          (open-account-with "GBP" nil (party-with :party-status-active))]
      (is (error/rejection? result))
      (is (= :cash-account/product-not-published (error/kind result)))
      (is (= "prd.001" (:product-id (error/payload result))))
      (is (= open-as-of (:as-of (error/payload result)))))))

(deftest open-account-currency-test
  (testing "a currency outside the product's allowed list is rejected"
    (let [result (open-account-with "EUR"
                                    (version-allowing ["GBP" "USD"])
                                    (party-with :party-status-active))]
      (is (error/rejection? result))
      (is (= :cash-account/invalid-currency (error/kind result)))))
  (testing "a currency in the allowed list passes"
    ;; The party guard runs next, so reaching it is what shows the
    ;; currency was accepted.
    (let [result (open-account-with "GBP"
                                    (version-allowing ["GBP" "USD"])
                                    (party-with :party-status-pending))]
      (is (= :cash-account/party-status (error/kind result)))))
  (testing "an empty allowed list is treated as unrestricted"
    (let [result (open-account-with "EUR"
                                    (version-allowing [])
                                    (party-with :party-status-pending))]
      (is (= :cash-account/party-status (error/kind result))))))

(deftest open-account-party-status-test
  (testing "opening for a party that isn't active is rejected"
    (doseq [status [:party-status-pending :party-status-closed]]
      (let [result (open-account-with "GBP"
                                      (version-allowing ["GBP"])
                                      (party-with status))]
        (is (error/rejection? result))
        (is (= :cash-account/party-status (error/kind result)))
        (is (= status (:status (error/payload result))))))))

(defn- version-with-schemes
  [schemes]
  (assoc (version-allowing ["GBP"]) :allowed-payment-address-schemes schemes))

(def ^:private scan-version
  (version-with-schemes [:payment-address-scheme-scan]))

(def ^:private allow-open [(policy-allowing :cash-account-action-open)])

(defn- counts
  "The two counts core reads before an open: the bank's total and the
  subtotal for this product type, account type and currency."
  [total subtotal]
  {:cash-account {#{:bank-id} total
                  #{:bank-id :product-type :account-type :currency} subtotal}})

(defn- open-account-past-the-guards
  "An open whose version, currency and party all pass, so the result
  is whatever the policies or the address allocation say."
  [product-version aggregates policies]
  (SUT/open-account (assoc open-data :currency "GBP")
                    product-version
                    open-as-of
                    (party-with :party-status-active)
                    (constantly "12345678")
                    aggregates
                    policies))

(deftest open-account-payment-scheme-test
  (testing "a version allowing no scheme cannot be addressed"
    (let [result (open-account-past-the-guards (version-with-schemes [])
                                               (counts 0 0)
                                               allow-open)]
      (is (error/rejection? result))
      (is (= :cash-account/no-payment-schemes (error/kind result)))))
  (testing "a scheme the fountain cannot allocate is named"
    (let [result (open-account-past-the-guards (version-with-schemes
                                                [:payment-address-scheme-iban])
                                               (counts 0 0)
                                               allow-open)]
      (is (error/rejection? result))
      (is (= :cash-account/unsupported-scheme (error/kind result)))))
  (testing "and a scan scheme takes its number from the fountain"
    (let [result
          (open-account-past-the-guards scan-version (counts 0 0) allow-open)]
      (is (= :cash-account-status-opening (:account-status result)))
      (is (= "04000412345678" (:bban result))))))

(defn- count-limit
  [bound filters]
  {:kind {:cash-account (if filters {:filters filters} {})}
   :bound {:kind {:max {:aggregate {:kind {:count
                                           {:value bound
                                            :window
                                            :time-window-instant}}}}}}
   :reason "Test bound"})

(defn- allow-open-within
  [& limits]
  [(assoc (policy-allowing :cash-account-action-open) :limits (vec limits))])

(deftest open-account-total-count-limit-test
  (let [two-in-total (allow-open-within (count-limit 2 nil))]
    (testing "the bank's total admits an open up to the bound"
      (let [result (open-account-past-the-guards scan-version
                                                 (counts 1 1)
                                                 two-in-total)]
        (is (= :cash-account-status-opening (:account-status result)))))
    (testing "and refuses the open past it"
      (let [result (open-account-past-the-guards scan-version
                                                 (counts 2 2)
                                                 two-in-total)]
        (is (error/rejection? result))
        (is (= :policy/limit-exceeded (error/kind result)))))))

(deftest open-account-filtered-count-limit-test
  (let [term-deposit
        (assoc scan-version :product-type :product-type-sub-ledger-term-deposit)
        one-term-deposit
        (allow-open-within
         (count-limit 1
                      [{:product-type :product-type-sub-ledger-term-deposit}]))]
    (testing "a limit filtered by product type bites on that type's subtotal"
      (let [result (open-account-past-the-guards term-deposit
                                                 (counts 5 1)
                                                 one-term-deposit)]
        (is (error/rejection? result))
        (is (= :policy/limit-exceeded (error/kind result)))))
    (testing "and leaves another product type alone at the same counts"
      (let [result (open-account-past-the-guards scan-version
                                                 (counts 5 1)
                                                 one-term-deposit)]
        (is (= :cash-account-status-opening (:account-status result)))))))

(deftest close-account-source-state-guard-test
  (testing
    "closing an account that is neither opened nor suspended is
           rejected, regardless of policy, and the rejection names both
           closeable statuses"
    (doseq [status [:cash-account-status-opening
                    :cash-account-status-closing
                    :cash-account-status-closed]]
      (let [result (SUT/close-account (account status) [] [])]
        (is (error/rejection? result))
        (is (= :cash-account/invalid-status (error/kind result)))
        (is (= status (:status (error/payload result))))
        (is (= #{:cash-account-status-opened :cash-account-status-suspended}
               (:allowed (error/payload result))))))))

(deftest close-account-from-suspended-test
  (testing "a suspended account closes without being resumed first"
    (let [result (SUT/close-account (account :cash-account-status-suspended)
                                    []
                                    [(policy-allowing
                                      :cash-account-action-close)])]
      (is (= :cash-account-status-closing (:account-status result))))))

(def ^:private posted-bucket
  {:balance-type :balance-type-default
   :balance-status :balance-status-posted
   :currency "GBP"
   :credit 500
   :debit 500})

(def ^:private pending-hold
  {:balance-type :balance-type-default
   :balance-status :balance-status-pending-outgoing
   :currency "GBP"
   :credit 0
   :debit 500})

(deftest close-account-non-zero-balance-test
  (let [acct (account :cash-account-status-opened)
        balances [posted-bucket pending-hold]]
    (testing
      "a bucket that doesn't net to zero is rejected by default, and
             the rejection names the buckets it refused on rather than
             a posted total"
      (let [result (SUT/close-account acct
                                      balances
                                      [(policy-allowing
                                        :cash-account-action-close)])
            payload (error/payload result)]
        (is (error/rejection? result))
        (is (= :cash-account/non-zero-on-close (error/kind result)))
        (is (= "acc.test" (:account-id payload)))
        (is (= [pending-hold] (:balances payload)))
        (is (not (contains? payload :posted-balance)))))
    (testing "a bucket carrying no type or status reports what it has"
      (let [result (SUT/close-account acct
                                      [{:credit 500 :debit 0}]
                                      [(policy-allowing
                                        :cash-account-action-close)])]
        (is (= [{:credit 500 :debit 0}] (:balances (error/payload result))))))
    (testing "an explicit opt-out capability allows the close"
      (let [result (SUT/close-account acct
                                      balances
                                      [(policy-allowing
                                        :cash-account-action-close
                                        :cash-account-action-close-non-zero)])]
        (is (= :cash-account-status-closing (:account-status result)))))))

(deftest close-account-zero-balance-test
  (testing
    "balances that net to zero across all buckets close without the
           opt-out capability"
    (let [acct (account :cash-account-status-opened)
          balances [{:credit 500 :debit 500} {:credit 0 :debit 0}]
          result (SUT/close-account acct
                                    balances
                                    [(policy-allowing
                                      :cash-account-action-close)])]
      (is (= :cash-account-status-closing (:account-status result))))))

(deftest suspend-account-source-state-guard-test
  (testing
    "suspending an account not in :cash-account-status-opened is
           rejected, regardless of policy"
    (doseq [status [:cash-account-status-opening
                    :cash-account-status-closing
                    :cash-account-status-closed
                    :cash-account-status-suspended]]
      (let [result (SUT/suspend-account (account status) [])]
        (is (error/rejection? result))
        (is (= :cash-account/invalid-status (error/kind result)))
        (is (= status (:status (error/payload result))))))))

(deftest resume-account-source-state-guard-test
  (testing
    "resuming an account not in :cash-account-status-suspended is
           rejected, regardless of policy"
    (doseq [status [:cash-account-status-opening
                    :cash-account-status-opened
                    :cash-account-status-closing
                    :cash-account-status-closed]]
      (let [result (SUT/resume-account (account status) [])]
        (is (error/rejection? result))
        (is (= :cash-account/invalid-status (error/kind result)))
        (is (= status (:status (error/payload result))))))))

(defn- opened-account-with-address
  []
  (assoc (account :cash-account-status-opened)
         :bban "04000412345678"
         :payment-addresses [{:scheme :payment-address-scheme-scan
                              :scan {:sort-code "040004"
                                     :account-number "12345678"}}]))

(def ^:private product-version
  {:allowed-payment-address-schemes [:payment-address-scheme-scan]})

(deftest rotate-address-source-state-guard-test
  (testing
    "rotating an account not in :cash-account-status-opened is
           rejected, regardless of policy"
    (doseq [status [:cash-account-status-opening
                    :cash-account-status-closing
                    :cash-account-status-closed
                    :cash-account-status-suspended]]
      (let [result (SUT/rotate-address (account status)
                                       {}
                                       product-version
                                       (constantly "99999999")
                                       [])]
        (is (error/rejection? result))
        (is (= :cash-account/invalid-status (error/kind result)))
        (is (= status (:status (error/payload result))))))))

(deftest rotate-address-happy-test
  (testing
    "rotating replaces the payment address, rewrites the bban, and
           retires the old address on-record"
    (let [acct (opened-account-with-address)
          result (SUT/rotate-address acct
                                     {:idempotency-key "ik-rotate-0000000001"}
                                     product-version
                                     (constantly "99999999")
                                     [(policy-allowing
                                       :cash-account-action-rotate-address)])]
      (is (= :cash-account-status-opened (:account-status result)))
      (is (= "04000499999999" (:bban result)))
      (is (= [{:scheme :payment-address-scheme-scan
               :scan {:sort-code "040004" :account-number "99999999"}}]
             (:payment-addresses result)))
      (is (= (:payment-addresses acct)
             (mapv :address (:retired-payment-addresses result))))
      (is (every? int? (map :retired-at (:retired-payment-addresses result))))
      (is (= "ik-rotate-0000000001" (:last-rotation-idempotency-key result))))))

(def ^:private migration-target {:product-id "prd.mega" :version-id "prv.4"})

(deftest migrate-product-source-state-guard-test
  (testing
    "migrating an account not in :cash-account-status-opened is
           rejected, regardless of policy"
    (doseq [status [:cash-account-status-opening
                    :cash-account-status-closing
                    :cash-account-status-closed
                    :cash-account-status-suspended]]
      (let [result (SUT/migrate-product (account status)
                                        migration-target
                                        [(policy-allowing
                                          :cash-account-action-migrate)])]
        (is (error/rejection? result))
        (is (= :cash-account/invalid-status (error/kind result)))
        (is (= status (:status (error/payload result))))))))

(deftest migrate-product-capability-test
  (testing "migrating without an allowing policy is denied"
    ;; Capability resolution is default-deny, so this also pins that
    ;; the migrate action is its own capability rather than riding on
    ;; another transition's.
    (let [result (SUT/migrate-product (account :cash-account-status-opened)
                                      migration-target
                                      [])]
      (is (error/anomaly? result)))))

(deftest migrate-product-happy-test
  (testing
    "migrating repins the product and version and leaves everything
           else alone"
    ;; The same account on different terms — a customer whose savings
    ;; rate changed did not get a new account, so the number, the
    ;; addresses and the status all stay put.
    (let [acct (opened-account-with-address)
          result (SUT/migrate-product acct
                                      migration-target
                                      [(policy-allowing
                                        :cash-account-action-migrate)])]
      (is (= "prd.mega" (:product-id result)))
      (is (= "prv.4" (:version-id result)))
      (is (= :cash-account-status-opened (:account-status result)))
      (is (= (:bban acct) (:bban result)))
      (is (= (:payment-addresses acct) (:payment-addresses result)))
      (is (= (:account-id acct) (:account-id result)))
      (is (int? (:updated-at result))))))
