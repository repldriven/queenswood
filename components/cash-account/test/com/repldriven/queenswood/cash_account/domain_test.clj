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
   :status status
   :currency "GBP"})

(defn- policy-allowing
  [& actions]
  {:status :policy-status-active
   :capabilities (mapv (fn [action]
                         {:effect :effect-allow
                          :kind {:cash-account {:action action}}})
                       actions)})

(def ^:private open-as-of 20468)

(def ^:private operator {:kind :actor-kind-operator :principal-id "test"})

(def ^:private open-data
  {:bank-id "bnk.test"
   :party-id "pty.test"
   :product-id "prd.001"
   :name "Test Account"
   :actor operator})

(defn- party-with
  [status]
  {:party-id "pty.test" :party-type :party-type-person :status status})

(defn- version-in
  [currency]
  {:version-id "prv.001"
   :product-type :account-product-type-sub-ledger-current
   :currency currency
   :allowed-payment-address-schemes [:payment-address-scheme-scan]})

(defn- open-account-with
  [currency product-version party]
  (SUT/open-account (assoc open-data :currency currency)
                    product-version
                    open-as-of
                    party
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
  (testing "a currency other than the version's is rejected"
    (let [result (open-account-with "EUR"
                                    (version-in "GBP")
                                    (party-with :party-status-active))]
      (is (error/rejection? result))
      (is (= :cash-account/invalid-currency (error/kind result)))))
  (testing "the version's currency passes"
    ;; The party guard runs next, so reaching it is what shows the
    ;; currency was accepted.
    (let [result (open-account-with "GBP"
                                    (version-in "GBP")
                                    (party-with :party-status-pending))]
      (is (= :cash-account/party-status (error/kind result))))))

(deftest open-account-party-status-test
  (testing "opening for a party that isn't active is rejected"
    (doseq [status [:party-status-pending :party-status-closed]]
      (let [result
            (open-account-with "GBP" (version-in "GBP") (party-with status))]
        (is (error/rejection? result))
        (is (= :cash-account/party-status (error/kind result)))
        (is (= status (:status (error/payload result))))))))

(defn- version-with-schemes
  [schemes]
  (assoc (version-in "GBP") :allowed-payment-address-schemes schemes))

(def ^:private scan-version
  (version-with-schemes [:payment-address-scheme-scan]))

(def ^:private allow-open [(policy-allowing :cash-account-action-open)])

(defn- aggregates
  "The two counts core reads before an open: the bank's total and the
  subtotal for this product type, account type and currency."
  [total subtotal]
  {:cash-account {#{:bank-id} total
                  #{:bank-id :product-type :account-type :currency} subtotal}})

(defn- open-account-past-the-guards
  "An open whose version, currency and party all pass, so the result
  is whatever the policies or the address schemes say."
  [product-version aggregates policies]
  (SUT/open-account (assoc open-data :currency "GBP")
                    product-version
                    open-as-of
                    (party-with :party-status-active)
                    aggregates
                    policies))

(deftest open-account-payment-scheme-test
  (testing "a version allowing no scheme cannot be addressed"
    (let [result (open-account-past-the-guards (version-with-schemes [])
                                               (aggregates 0 0)
                                               allow-open)]
      (is (error/rejection? result))
      (is (= :cash-account/no-payment-schemes (error/kind result)))))
  (testing "a scheme the provider is not asked for is named"
    (let [result (open-account-past-the-guards (version-with-schemes
                                                [:payment-address-scheme-iban])
                                               (aggregates 0 0)
                                               allow-open)]
      (is (error/rejection? result))
      (is (= :cash-account/unsupported-scheme (error/kind result)))))
  (testing "and a scan account opens with no address until the provider's"
    (let [result (open-account-past-the-guards scan-version
                                               (aggregates 0 0)
                                               allow-open)]
      (is (= :cash-account-status-opening (:status result)))
      (is (= [] (:payment-addresses result)))
      (is (not (contains? result :bban))))))

(deftest address-schemes-test
  (testing "a scan version asks the provider for scan"
    (is (= ["scan"] (SUT/address-schemes scan-version)))))

(def ^:private issued
  {:provider-account-id "va-1"
   :addresses
   [{:scheme "scan" :sort-code "040004" :account-number "20000001"}]})

(deftest provider-opened-account-test
  (testing "the provider's account and addresses open the account"
    (let [result (SUT/provider-opened-account (account
                                               :cash-account-status-opening)
                                              issued)]
      (is (= :cash-account-status-opened (:status result)))
      (is (= "va-1" (:provider-account-id result)))
      (is (= "04000420000001" (:bban result)))
      (is (= [{:scheme :payment-address-scheme-scan
               :scan {:sort-code "040004" :account-number "20000001"}}]
             (:payment-addresses result))))))

(deftest refused-account-test
  (testing "a declined opening is refused with the provider's reason"
    (let [result (SUT/refused-account (account :cash-account-status-opening)
                                      "The account was declined")]
      (is (= :cash-account-status-refused (:status result)))
      (is (= "The account was declined" (:refused-reason result)))
      (is (number? (:updated-at result))))))

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
                                                 (aggregates 1 1)
                                                 two-in-total)]
        (is (= :cash-account-status-opening (:status result)))))
    (testing "and refuses the open past it"
      (let [result (open-account-past-the-guards scan-version
                                                 (aggregates 2 2)
                                                 two-in-total)]
        (is (error/rejection? result))
        (is (= :policy/limit-exceeded (error/kind result)))))))

(deftest open-account-filtered-count-limit-test
  (let [term-deposit (assoc scan-version
                            :product-type
                            :account-product-type-sub-ledger-term-deposit)
        one-term-deposit (allow-open-within
                          (count-limit
                           1
                           [{:product-type
                             :account-product-type-sub-ledger-term-deposit}]))]
    (testing "a limit filtered by product type bites on that type's subtotal"
      (let [result (open-account-past-the-guards term-deposit
                                                 (aggregates 5 1)
                                                 one-term-deposit)]
        (is (error/rejection? result))
        (is (= :policy/limit-exceeded (error/kind result)))))
    (testing "and leaves another product type alone at the same counts"
      (let [result (open-account-past-the-guards scan-version
                                                 (aggregates 5 1)
                                                 one-term-deposit)]
        (is (= :cash-account-status-opening (:status result)))))))

(def ^:private may-close [(policy-allowing :cash-account-action-close)])

(def ^:private may-close-non-zero
  [(policy-allowing :cash-account-action-close
                    :cash-account-action-close-non-zero)])

(defn- close
  ([acct]
   (close acct nil may-close))
  ([acct latest policies]
   (SUT/close-account acct latest operator policies)))

(deftest close-account-source-state-guard-test
  (testing
    "closing an account that is neither opened, suspended nor closing
           is rejected, regardless of policy, and the rejection names
           every closeable status"
    (doseq [status [:cash-account-status-opening :cash-account-status-closed]]
      (let [result (close (account status) nil [])]
        (is (error/rejection? result))
        (is (= :cash-account/invalid-status (error/kind result)))
        (is (= status (:status (error/payload result))))
        (is (= #{:cash-account-status-opened :cash-account-status-suspended
                 :cash-account-status-closing}
               (:allowed (error/payload result))))))))

(deftest close-account-from-suspended-test
  (testing "a suspended account closes without being resumed first"
    (let [result (close (account :cash-account-status-suspended))]
      (is (= :cash-account-status-closing (:status result)))
      (is (= operator (:updated-by result)))))
  (testing "a closing account may be closed again"
    (is (= :cash-account-status-closing
           (:status (close (account :cash-account-status-closing)))))))

(deftest close-account-in-progress-test
  (let [closing (account :cash-account-status-closing)
        operation (SUT/new-operation closing
                                     :cash-account-operation-kind-close
                                     20500
                                     {:actor operator}
                                     nil)]
    (testing "a close still being carried out refuses another"
      (let [result (close closing operation may-close)]
        (is (= :cash-account/close-in-progress (error/kind result)))
        (is (= (:operation-id operation)
               (:operation-id (error/payload result))))))
    (testing "a close already refused does not"
      (is (= :cash-account-status-closing
             (:status (close closing
                             (SUT/refused-operation operation "held")
                             may-close)))))))

(deftest close-refused-account-test
  (testing
    "a refused close returns the account to the status its
           operation was asked from"
    (doseq [from [:cash-account-status-opened :cash-account-status-suspended]]
      (let [closing (close (account from))
            operation (SUT/new-operation (account from)
                                         :cash-account-operation-kind-close
                                         20500
                                         {:actor operator}
                                         nil)
            result (SUT/close-refused-account closing operation)]
        (is (= from (:account-status operation)))
        (is (= from (:status result)))
        (is (= {:kind :actor-kind-platform :principal-id "cash-account"}
               (:updated-by result)))))))

(deftest a-close-waits-on-its-closure-test
  (testing
    "a close is pending until it is carried out, even of an account no
           provider holds, and records the terms it is carried out by"
    (let [terms {:nominated-account-id "acc.savings"
                 :closure-interest {:kind {:pay {}}}
                 :closure-fee {:kind {:flat {:amount 500}}}}
          operation (SUT/new-operation (account :cash-account-status-opened)
                                       :cash-account-operation-kind-close
                                       20500
                                       {:actor operator}
                                       terms)]
      (is (SUT/pending-operation? operation))
      (is (= terms
             (select-keys operation
                          [:nominated-account-id :closure-interest
                           :closure-fee]))))))

(def ^:private posted-bucket
  {:balance-type :balance-type-default
   :balance-status :balance-status-posted
   :currency "GBP"
   :credit 1500
   :debit 500})

(def ^:private accrued-bucket
  {:balance-type :balance-type-interest-accrued
   :balance-status :balance-status-posted
   :currency "GBP"
   :credit 25
   :debit 0})

(def ^:private pending-hold
  {:balance-type :balance-type-default
   :balance-status :balance-status-pending-outgoing
   :currency "GBP"
   :credit 0
   :debit 500})

(def ^:private holder
  (assoc (account :cash-account-status-opened) :party-id "pty.test"))

(def ^:private savings (assoc holder :account-id "acc.savings"))

(def ^:private pay-term {:interest {:kind {:pay {}}}})

(defn- closable
  ([balances]
   (closable balances pay-term "acc.savings" savings may-close))
  ([balances term nominated-account-id nominated policies]
   (SUT/check-closable holder
                       balances
                       term
                       nominated-account-id
                       nominated
                       policies)))

(deftest check-closable-buckets-test
  (testing "a posted default or interest-accrued balance does not refuse it"
    (is (nil? (closable [posted-bucket accrued-bucket]))))
  (testing
    "a pending hold is refused by default, and the rejection names
           the buckets it refused on"
    (let [result (closable [posted-bucket pending-hold])
          payload (error/payload result)]
      (is (= :cash-account/non-zero-on-close (error/kind result)))
      (is (= "acc.test" (:account-id payload)))
      (is (= [pending-hold] (:balances payload)))))
  (testing "a bucket carrying no type or status reports what it has"
    (let [result (closable [{:credit 500 :debit 0}])]
      (is (= [{:credit 500 :debit 0}] (:balances (error/payload result))))))
  (testing "an explicit opt-out capability allows the close"
    (is (nil? (closable [posted-bucket pending-hold]
                        pay-term
                        "acc.savings"
                        savings
                        may-close-non-zero)))))

(deftest check-closable-nominated-account-test
  (testing "another opened account of the party in its currency qualifies"
    (is (nil? (closable [posted-bucket]))))
  (testing "anything else is refused, naming the nominated account"
    (doseq [[label nominated]
            [["not found" nil]
             ["itself" holder]
             ["another party's" (assoc savings :party-id "pty.x")]
             ["in another currency" (assoc savings :currency "EUR")]
             ["not opened"
              (assoc savings :status :cash-account-status-suspended)]]]
      (let [result (closable [] pay-term "acc.savings" nominated may-close)]
        (is (= :cash-account/invalid-nominated-account (error/kind result))
            label)
        (is (= "acc.savings" (:nominated-account-id (error/payload result)))
            label)))))

(deftest check-closable-held-test
  (testing "nothing held closes with no nominated account"
    (is (nil? (closable [] pay-term nil nil may-close))))
  (testing "a balance with nowhere to go is refused, saying to name one"
    (let [result (closable [posted-bucket] pay-term nil nil may-close)]
      (is (= :cash-account/non-zero-on-close (error/kind result)))
      (is (re-find #"nominated account" (:message (error/payload result))))))
  (testing "so is interest the term pays, but not interest it forfeits"
    (is (error/rejection?
         (closable [accrued-bucket] pay-term nil nil may-close)))
    (is (nil? (closable [accrued-bucket]
                        {:interest {:kind {:forfeit {}}}}
                        nil
                        nil
                        may-close))))
  (testing "unless the bank's policies waive it"
    (is (nil? (closable [posted-bucket] pay-term nil nil may-close-non-zero))))
  (testing "an overdrawn account is refused even with a nominated account"
    (let [result (closable [(assoc posted-bucket :credit 0)])]
      (is (= :cash-account/non-zero-on-close (error/kind result)))
      (is (= "Account is overdrawn" (:message (error/payload result)))))))

(def ^:private closure-terms
  "No closure in the first three months, then ten days' interest and 1%
  of the balance, between 5.00 and 20.00, until the twelfth."
  {:interest {:kind {:pay {}}}
   :early [{:unit :interest-period-unit-month :count 3 :kind {:refused {}}}
           {:unit :interest-period-unit-month
            :count 12
            :kind {:charged {:interest {:kind {:forfeit-days {:days 10}}}
                             :fee {:kind {:balance {:rate-bps 100
                                                    :min 500
                                                    :max 2000}}}}}}]})

(def ^:private from-on
  "2026-01-31, the epoch day the account came onto its version."
  20484)

(deftest closure-term-test
  (let [acct (assoc holder :version-from-on from-on)]
    (testing "inside a refused window the close is refused, naming its end"
      (let [result (SUT/closure-term acct closure-terms (+ from-on 30))]
        (is (= :cash-account/early-closure-refused (error/kind result)))
        (is (= "2026-04-30" (:closable-from (error/payload result))))))
    (testing "a closure on the day a window ends is in the next one"
      (is (error/rejection? (SUT/closure-term acct closure-terms 20572)))
      (is (= {:kind {:forfeit-days {:days 10}}}
             (:interest (SUT/closure-term acct closure-terms 20573)))))
    (testing "a charged window's interest term and fee hold inside it"
      (is (= (get-in closure-terms [:early 1 :kind :charged])
             (SUT/closure-term acct closure-terms (+ from-on 200)))))
    (testing "after the last window the terms' own interest term holds"
      (is (= {:interest {:kind {:pay {}}}}
             (SUT/closure-term acct closure-terms (+ from-on 400)))))
    (testing "terms with no windows hold from the first day"
      (is (= {:interest {:kind {:pay {}}}}
             (SUT/closure-term acct {:interest {:kind {:pay {}}}} from-on))))))

(deftest emptied-test
  (is (SUT/emptied? [{:credit 500 :debit 500} {:credit 0 :debit 0}]))
  (is (not (SUT/emptied? [accrued-bucket]))))

(deftest suspend-account-source-state-guard-test
  (testing
    "suspending an account not in :cash-account-status-opened is
           rejected, regardless of policy"
    (doseq [status [:cash-account-status-opening
                    :cash-account-status-closing
                    :cash-account-status-closed
                    :cash-account-status-suspended]]
      (let [result (SUT/suspend-account (account status) operator [])]
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
      (let [result (SUT/resume-account (account status) operator {} [])]
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

(deftest rotate-address-source-state-guard-test
  (testing
    "rotating an account not in :cash-account-status-opened is
           rejected, regardless of policy"
    (doseq [status [:cash-account-status-opening
                    :cash-account-status-closing
                    :cash-account-status-closed
                    :cash-account-status-suspended]]
      (let [result (SUT/request-rotation (account status) {} [])]
        (is (error/rejection? result))
        (is (= :cash-account/invalid-status (error/kind result)))
        (is (= status (:status (error/payload result))))))))

(defn- operations-today
  [n]
  {:cash-account-operation {#{:bank-id :account-id :requested-on} n}})

(def ^:private five-operations-a-day
  {:status :policy-status-active
   :limits [{:kind {:cash-account-operation {}}
             :bound {:kind {:max {:aggregate
                                  {:kind {:count {:value 5
                                                  :window
                                                  :time-window-daily}}}}}}}]})

(deftest rotate-address-happy-test
  (testing
    "a rotation leaves the account as it is until the provider
           answers"
    (let [acct (opened-account-with-address)]
      (is (= acct
             (SUT/request-rotation acct
                                   (operations-today 0)
                                   [(policy-allowing
                                     :cash-account-action-rotate-address)])))))
  (testing
    "the provider's new address replaces the old, which is retired
           on-record"
    (let [acct (opened-account-with-address)
          result (SUT/reissued-account acct
                                       {:provider-account-id "va-1"
                                        :addresses [{:scheme "scan"
                                                     :sort-code "040004"
                                                     :account-number
                                                     "99999999"}]})]
      (is (= "04000499999999" (:bban result)))
      (is (= [{:scheme :payment-address-scheme-scan
               :scan {:sort-code "040004" :account-number "99999999"}}]
             (:payment-addresses result)))
      (is (= (:payment-addresses acct)
             (mapv :address (:retired-payment-addresses result))))
      (is (every? int?
                  (map :retired-at (:retired-payment-addresses result)))))))

(def ^:private migration-target {:product-id "prd.mega" :version-id "prv.4"})

(def ^:private mover
  {:kind :actor-kind-platform :principal-id "cash-account-migration"})

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
                                        20500
                                        [(policy-allowing
                                          :cash-account-action-migrate)]
                                        mover)]
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
                                      20500
                                      []
                                      mover)]
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
                                      20500
                                      [(policy-allowing
                                        :cash-account-action-migrate)]
                                      mover)]
      (is (= "prd.mega" (:product-id result)))
      (is (= "prv.4" (:version-id result)))
      (is (= 20500 (:version-from-on result)))
      (is (= :cash-account-status-opened (:status result)))
      (is (= (:bban acct) (:bban result)))
      (is (= (:payment-addresses acct) (:payment-addresses result)))
      (is (= (:account-id acct) (:account-id result)))
      (is (int? (:updated-at result))))))

(def ^:private member {:kind :actor-kind-member :principal-id "usr.1"})

(deftest an-operation-waits-on-its-provider-test
  (let [held (assoc (account :cash-account-status-suspended)
                    :bank-id "bnk.test"
                    :provider-account-id "prv-1")
        pending (SUT/new-operation held
                                   :cash-account-operation-kind-suspend
                                   20500
                                   {:actor member :idempotency-key "ik-1"}
                                   nil)]
    (testing "an account its provider holds waits on the provider"
      (is (SUT/pending-operation? pending))
      (is (= :cash-account-operation-kind-suspend (:kind pending)))
      (is (= member (:created-by pending)))
      (is (= :cash-account-status-suspended (:account-status pending)))
      (is (= "ik-1" (:idempotency-key pending)))
      (is (re-find #"^cao\." (:operation-id pending))))
    (testing "one with no account at its provider has nothing to wait on"
      (is (= :cash-account-operation-status-completed
             (:status (SUT/new-operation (dissoc held :provider-account-id)
                                         :cash-account-operation-kind-resume
                                         20500
                                         {:actor member}
                                         nil)))))
    (testing "the provider's answer settles it, by the platform"
      (is (= :cash-account-operation-status-completed
             (:status (SUT/completed-operation pending))))
      (let [refused (SUT/refused-operation pending "blocked by provider")]
        (is (= :cash-account-operation-status-refused (:status refused)))
        (is (= "blocked by provider" (:refused-reason refused)))
        (is (= :actor-kind-platform (get-in refused [:updated-by :kind])))))))

(deftest an-account-takes-its-daily-operations-test
  (let [policies [(policy-allowing :cash-account-action-suspend
                                   :cash-account-action-resume)
                  five-operations-a-day]
        suspended (account :cash-account-status-suspended)]
    (testing "a resume within the day's operations is allowed"
      (is (= :cash-account-status-opened
             (:status (SUT/resume-account suspended
                                          operator
                                          (operations-today 4)
                                          policies)))))
    (testing "one past them is refused"
      (is (= :policy/limit-exceeded
             (error/kind (SUT/resume-account suspended
                                             operator
                                             (operations-today 5)
                                             policies)))))
    (testing "a suspend never is"
      (is (= :cash-account-status-suspended
             (:status (SUT/suspend-account (account :cash-account-status-opened)
                                           operator
                                           policies)))))))

(deftest a-rotation-is-held-to-the-daily-operations-test
  (let [policies [(policy-allowing :cash-account-action-rotate-address)
                  five-operations-a-day]]
    (testing "a rotation within the day's operations is allowed"
      (is (= :cash-account-status-opened
             (:status (SUT/request-rotation (opened-account-with-address)
                                            (operations-today 4)
                                            policies)))))
    (testing "one past them is refused"
      (is (= :policy/limit-exceeded
             (error/kind (SUT/request-rotation (opened-account-with-address)
                                               (operations-today 5)
                                               policies)))))))
