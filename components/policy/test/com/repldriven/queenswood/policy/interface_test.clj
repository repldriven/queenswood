(ns com.repldriven.queenswood.policy.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.policy.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(defn- fdb-config
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(defn- policy
  [capabilities & {:keys [enabled] :or {enabled true}}]
  {:enabled enabled
   :capabilities capabilities})

(defn- allow
  ([kind] (allow kind nil))
  ([kind reason]
   (cond-> {:effect :effect-allow :kind kind}
           reason
           (assoc :reason reason))))

(defn- deny
  ([kind] (deny kind nil))
  ([kind reason]
   (cond-> {:effect :effect-deny :kind kind}
           reason
           (assoc :reason reason))))

(deftest check-capability-test
  (testing "exact action allow with no filter"
    (let [policies [(policy [(allow {:organization
                                     {:action :organization-action-create}})])]]
      (is (true? (SUT/check-capability policies
                                       :organization
                                       {:action
                                        :organization-action-create})))))
  (testing "empty filter list matches any request"
    (let [policies [(policy [(allow {:organization {:action
                                                    :organization-action-create
                                                    :filters []}})])]]
      (is (true? (SUT/check-capability policies
                                       :organization
                                       {:action :organization-action-create
                                        :type :organization-type-internal
                                        :status :organization-status-live})))))
  (testing "tuple with unset (nil) slots does not constrain"
    (let [policies [(policy [(allow {:organization
                                     {:action :organization-action-create
                                      :filters [{:type nil :status nil}]}})])]]
      (is (true? (SUT/check-capability policies
                                       :organization
                                       {:action :organization-action-create
                                        :type :organization-type-internal
                                        :status :organization-status-live})))))
  (testing "tuple with unset (:*-unknown) enum slots does not constrain"
    (let [policies [(policy [(allow {:organization
                                     {:action :organization-action-create
                                      :filters
                                      [{:type :organization-type-unknown
                                        :status
                                        :organization-status-unknown}]}})])]]
      (is (true? (SUT/check-capability policies
                                       :organization
                                       {:action :organization-action-create
                                        :type :organization-type-internal
                                        :status :organization-status-live})))))
  (testing "single-tuple match — all populated slots agree"
    (let [policies [(policy [(allow {:organization
                                     {:action :organization-action-create
                                      :filters
                                      [{:type
                                        :organization-type-customer}]}})])]]
      (is (true? (SUT/check-capability policies
                                       :organization
                                       {:action :organization-action-create
                                        :type :organization-type-customer})))))
  (testing "single-tuple miss — populated slot disagrees"
    (let [policies [(policy [(allow {:organization
                                     {:action :organization-action-create
                                      :filters
                                      [{:type
                                        :organization-type-internal}]}})])]
          result (SUT/check-capability policies
                                       :organization
                                       {:action :organization-action-create
                                        :type :organization-type-customer})]
      (is (error/unauthorized? result))
      (is (= :policy/denied (error/kind result)))))
  (testing "multi-tuple OR — request matches one of two tuples"
    (let [policies [(policy [(allow {:organization
                                     {:action :organization-action-create
                                      :filters
                                      [{:type :organization-type-customer}
                                       {:type
                                        :organization-type-internal}]}})])]]
      (is (true? (SUT/check-capability policies
                                       :organization
                                       {:action :organization-action-create
                                        :type :organization-type-internal})))))
  (testing "deny wins over allow on same request"
    (let [kind {:organization {:action :organization-action-create}}
          policies [(policy [(allow kind) (deny kind "explicitly forbidden")])]
          result (SUT/check-capability policies
                                       :organization
                                       {:action :organization-action-create})]
      (is (error/unauthorized? result))
      (is (= "explicitly forbidden" (:message (error/payload result))))))
  (testing "variant mismatch denies"
    (let [policies [(policy [(allow {:cash-account
                                     {:action :cash-account-action-open}})])]
          result (SUT/check-capability policies
                                       :organization
                                       {:action :organization-action-create})]
      (is (error/unauthorized? result))))
  (testing "disabled policy is ignored"
    (let [policies [(policy [(allow {:organization
                                     {:action :organization-action-create}})]
                            :enabled
                            false)]
          result (SUT/check-capability policies
                                       :organization
                                       {:action :organization-action-create})]
      (is (error/unauthorized? result))))
  (testing "empty policies denies"
    (let [result (SUT/check-capability []
                                       :organization
                                       {:action :organization-action-create})]
      (is (error/unauthorized? result)))))

(defn- limit-policy
  [limits & {:keys [enabled] :or {enabled true}}]
  {:enabled enabled :limits limits})

(defn- limit
  ([kind bound] (limit kind bound nil))
  ([kind bound reason] (limit kind bound reason nil))
  ([kind bound reason allow]
   (cond-> {:kind kind :bound bound}
           reason
           (assoc :reason reason)
           allow
           (assoc :allow allow))))

(defn- max-bound
  [agg-kind value window]
  {:kind {:max {:aggregate
                {:kind {agg-kind {:value value :window window}}}}}})

(defn- min-bound
  [agg-kind value window]
  {:kind {:min {:aggregate
                {:kind {agg-kind {:value value :window window}}}}}})

(defn- range-bound
  [agg-kind min-value max-value window]
  {:kind {:range
          {:min {:kind {agg-kind {:value min-value :window window}}}
           :max {:kind {agg-kind {:value max-value :window window}}}}}})

(deftest check-limit-test
  (testing "empty policies => true"
    (is (true? (SUT/check-limit
                []
                :internal-payment
                {:aggregate :count :window :time-window-instant :value 1}))))
  (testing "no matching limit kind => true"
    (let [policies [(limit-policy
                     [(limit {:cash-account {}}
                             (max-bound :count 1 :time-window-instant))])]]
      (is (true? (SUT/check-limit policies
                                  :internal-payment
                                  {:aggregate :count
                                   :window :time-window-instant
                                   :value 999})))))
  (testing "max bound at value passes"
    (let [policies [(limit-policy
                     [(limit {:internal-payment {}}
                             (max-bound :count 5 :time-window-instant))])]]
      (is (true? (SUT/check-limit
                  policies
                  :internal-payment
                  {:aggregate :count :window :time-window-instant :value 5})))))
  (testing "max bound exceeded => unauthorized with reason"
    (let [policies [(limit-policy [(limit
                                    {:internal-payment {}}
                                    (max-bound :count 5 :time-window-instant)
                                    "max 5 payments")])]
          result (SUT/check-limit
                  policies
                  :internal-payment
                  {:aggregate :count :window :time-window-instant :value 6})]
      (is (error/rejection? result))
      (is (= :policy/limit-exceeded (error/kind result)))
      (is (= "max 5 payments" (:message (error/payload result))))))
  (testing "min bound violated => unauthorized"
    (let [policies [(limit-policy
                     [(limit {:cash-account {}}
                             (min-bound :count 1 :time-window-instant))])]
          result (SUT/check-limit
                  policies
                  :cash-account
                  {:aggregate :count :window :time-window-instant :value 0})]
      (is (error/rejection? result))))
  (testing "range bound max exceeded => unauthorized"
    (let [policies [(limit-policy
                     [(limit {:cash-account {}}
                             (range-bound :count 1 1 :time-window-instant))])]
          result (SUT/check-limit
                  policies
                  :cash-account
                  {:aggregate :count :window :time-window-instant :value 2})]
      (is (error/rejection? result))))
  (testing "aggregate kind mismatch is skipped (count req vs amount limit)"
    (let [policies [(limit-policy
                     [(limit {:internal-payment {}}
                             (max-bound :amount 5 :time-window-instant))])]]
      (is (true? (SUT/check-limit policies
                                  :internal-payment
                                  {:aggregate :count
                                   :window :time-window-instant
                                   :value 999})))))
  (testing "window mismatch is skipped"
    (let [policies [(limit-policy [(limit
                                    {:internal-payment {}}
                                    (max-bound :count 5 :time-window-daily))])]]
      (is (true? (SUT/check-limit policies
                                  :internal-payment
                                  {:aggregate :count
                                   :window :time-window-instant
                                   :value 999})))))
  (testing "disabled policy is ignored"
    (let [policies [(limit-policy [(limit
                                    {:internal-payment {}}
                                    (max-bound :count 5 :time-window-instant))]
                                  :enabled
                                  false)]]
      (is (true? (SUT/check-limit policies
                                  :internal-payment
                                  {:aggregate :count
                                   :window :time-window-instant
                                   :value 999})))))
  (testing "tuple-filter mismatch skips the limit"
    (let [policies [(limit-policy [(limit
                                    {:cash-account {:filters
                                                    [{:account-type
                                                      :account-type-personal}]}}
                                    (max-bound :count 0 :time-window-instant)
                                    "no personal accounts")])]]
      (is (true? (SUT/check-limit policies
                                  :cash-account
                                  {:aggregate :count
                                   :window :time-window-instant
                                   :value 1
                                   :account-type :account-type-business})))))
  (testing "tuple-filter match — limit applies and bound is checked"
    (let [policies [(limit-policy
                     [(limit {:cash-account
                              {:filters
                               [{:product-type :product-type-sub-ledger-current
                                 :account-type :account-type-business}]}}
                             (max-bound :count 1 :time-window-instant)
                             "max 1 business current")])]
          result (SUT/check-limit policies
                                  :cash-account
                                  {:aggregate :count
                                   :window :time-window-instant
                                   :value 2
                                   :product-type
                                   :product-type-sub-ledger-current
                                   :account-type :account-type-business})]
      (is (error/rejection? result))))
  (testing "multi-tuple OR — limit applies when any tuple matches"
    (let [policies [(limit-policy
                     [(limit {:organization
                              {:filters [{:type :organization-type-customer}
                                         {:type :organization-type-internal}]}}
                             (max-bound :count 1 :time-window-instant)
                             "max 1")])]
          result (SUT/check-limit policies
                                  :organization
                                  {:aggregate :count
                                   :window :time-window-instant
                                   :value 2
                                   :type :organization-type-internal})]
      (is (error/rejection? result))))
  (testing "tuple unset slot does not constrain"
    (let [policies [(limit-policy [(limit
                                    {:organization {:filters [{:type nil}]}}
                                    (max-bound :count 0 :time-window-instant)
                                    "max 0 of any type")])]
          result (SUT/check-limit policies
                                  :organization
                                  {:aggregate :count
                                   :window :time-window-instant
                                   :value 1
                                   :type :organization-type-customer})]
      (is (error/rejection? result))))
  (testing "BalanceLimit filter without transaction-type fires for any type"
    (let [policies [(limit-policy [(limit {:balance {:filters
                                                     [{:kind {:computed
                                                              {:name
                                                               "available"}}}]}}
                                          (min-bound :amount
                                                     {:value 0 :currency "GBP"}
                                                     :time-window-instant)
                                          "available >= 0")])]
          result (SUT/check-limit policies
                                  :balance
                                  {:kind {:computed {:name "available"}}
                                   :transaction-type
                                   :transaction-type-interest-accrual
                                   :aggregate :amount
                                   :window :time-window-instant
                                   :value {:value -10 :currency "GBP"}})]
      (is (error/rejection? result))))
  (testing "BalanceLimit filter with transaction-type scopes the limit"
    (let [policies [(limit-policy
                     [(limit {:balance
                              {:filters
                               [{:kind {:computed {:name "available"}}
                                 :transaction-type
                                 :transaction-type-internal-transfer}]}}
                             (min-bound :amount
                                        {:value 0 :currency "GBP"}
                                        :time-window-instant)
                             "user transfers must keep available >= 0")])]]
      (testing "matching transaction-type triggers the limit"
        (let [result (SUT/check-limit policies
                                      :balance
                                      {:kind {:computed {:name "available"}}
                                       :transaction-type
                                       :transaction-type-internal-transfer
                                       :aggregate :amount
                                       :window :time-window-instant
                                       :value {:value -10 :currency "GBP"}})]
          (is (error/rejection? result))))
      (testing "non-matching transaction-type skips the limit"
        (is (true? (SUT/check-limit policies
                                    :balance
                                    {:kind {:computed {:name "available"}}
                                     :transaction-type
                                     :transaction-type-interest-accrual
                                     :aggregate :amount
                                     :window :time-window-instant
                                     :value {:value -10 :currency "GBP"}}))))))
  (testing "allow-improving — pre out-of-bound, post no worse, passes"
    (let [policies [(limit-policy [(limit {:balance {:filters
                                                     [{:kind {:computed
                                                              {:name
                                                               "available"}}}]}}
                                          (min-bound :amount
                                                     {:value 0 :currency "GBP"}
                                                     :time-window-instant)
                                          "available >= 0"
                                          :limit-allow-improving)])]]
      (is (true? (SUT/check-limit policies
                                  :balance
                                  {:kind {:computed {:name "available"}}
                                   :aggregate :amount
                                   :window :time-window-instant
                                   :pre-value {:value -50 :currency "GBP"}
                                   :value {:value -50 :currency "GBP"}})))
      (is (true? (SUT/check-limit policies
                                  :balance
                                  {:kind {:computed {:name "available"}}
                                   :aggregate :amount
                                   :window :time-window-instant
                                   :pre-value {:value -50 :currency "GBP"}
                                   :value {:value -40 :currency "GBP"}})))))
  (testing "allow-improving — pre out-of-bound, post worse, fails"
    (let [policies [(limit-policy [(limit {:balance {:filters
                                                     [{:kind {:computed
                                                              {:name
                                                               "available"}}}]}}
                                          (min-bound :amount
                                                     {:value 0 :currency "GBP"}
                                                     :time-window-instant)
                                          "available >= 0"
                                          :limit-allow-improving)])]
          result (SUT/check-limit policies
                                  :balance
                                  {:kind {:computed {:name "available"}}
                                   :aggregate :amount
                                   :window :time-window-instant
                                   :pre-value {:value -50 :currency "GBP"}
                                   :value {:value -60 :currency "GBP"}})]
      (is (error/rejection? result))))
  (testing "allow-improving — pre in-bound, post out-of-bound, fails"
    (let [policies [(limit-policy [(limit {:balance {:filters
                                                     [{:kind {:computed
                                                              {:name
                                                               "available"}}}]}}
                                          (min-bound :amount
                                                     {:value 0 :currency "GBP"}
                                                     :time-window-instant)
                                          "available >= 0"
                                          :limit-allow-improving)])]
          result (SUT/check-limit policies
                                  :balance
                                  {:kind {:computed {:name "available"}}
                                   :aggregate :amount
                                   :window :time-window-instant
                                   :pre-value {:value 10 :currency "GBP"}
                                   :value {:value -1 :currency "GBP"}})]
      (is (error/rejection? result))))
  (testing "allow-improving — post in-bound passes regardless of pre"
    (let [policies [(limit-policy [(limit {:balance {:filters
                                                     [{:kind {:computed
                                                              {:name
                                                               "available"}}}]}}
                                          (min-bound :amount
                                                     {:value 0 :currency "GBP"}
                                                     :time-window-instant)
                                          "available >= 0"
                                          :limit-allow-improving)])]]
      (is (true? (SUT/check-limit policies
                                  :balance
                                  {:kind {:computed {:name "available"}}
                                   :aggregate :amount
                                   :window :time-window-instant
                                   :pre-value {:value -50 :currency "GBP"}
                                   :value {:value 1 :currency "GBP"}})))))
  (testing "strict default — pre out-of-bound, post no worse, still fails"
    (let [policies [(limit-policy [(limit {:balance {:filters
                                                     [{:kind {:computed
                                                              {:name
                                                               "available"}}}]}}
                                          (min-bound :amount
                                                     {:value 0 :currency "GBP"}
                                                     :time-window-instant)
                                          "available >= 0")])]
          result (SUT/check-limit policies
                                  :balance
                                  {:kind {:computed {:name "available"}}
                                   :aggregate :amount
                                   :window :time-window-instant
                                   :pre-value {:value -50 :currency "GBP"}
                                   :value {:value -40 :currency "GBP"}})]
      (is (error/rejection? result)))))

(deftest labels-roundtrip-test
  (with-test-system
   [sys "classpath:policy/application-test.yml"]
   (let [config (fdb-config sys)]
     (testing "labels roundtrip via new-policy + get-policy"
       (nom-test> [{:keys [policy-id]} (SUT/new-policy
                                        config
                                        {:name "Roundtrip"
                                         :enabled true
                                         :category :policy-category-standard
                                         :capabilities []
                                         :limits []
                                         :labels {"tier" "test-tier"}})
                   loaded (SUT/get-policy config policy-id)
                   _ (is (= {"tier" "test-tier"} (:labels loaded)))])))))

(deftest get-effective-policies-test
  (with-test-system
   [sys "classpath:policy/application-test.yml"]
   (let [config (fdb-config sys)]
     (testing "scan returns the platform policy with labels populated"
       (nom-test> [{:keys [items]} (SUT/get-policies config)
                   p (first (filter (fn [p] (= "Platform policy" (:name p)))
                                    items))
                   _ (is (some? p))
                   _ (is (= {"tier" "platform"} (:labels p)))]))
     (testing "returns the platform policy via Policy_by_label index"
       (nom-test> [policies (SUT/get-effective-policies config {})
                   _ (is (= 1 (count policies)))
                   p (first policies)
                   _ (is (= "Platform policy" (:name p)))
                   _ (is (= "platform" (get-in p [:labels "tier"])))])))))

;; Pure evaluation-exclusion: `live?` gates archived policies out of the
;; capability/limit matchers, the same way `:enabled false` does. In-memory
;; policy maps (no persistence) — mirrors the other check-* tests.
(deftest archived-policy-excluded-from-evaluation-test
  (let [cap (allow {:organization {:action :organization-action-create}})
        req {:action :organization-action-create}
        with-status (fn [status]
                      {:enabled true :status status :capabilities [cap]})]
    (testing "an active policy's capability is in effect"
      (is (true? (SUT/check-capability [(with-status :policy-status-active)]
                                       :organization
                                       req))))
    (testing "a policy with no status set is treated as active"
      (is (true? (SUT/check-capability [{:enabled true :capabilities [cap]}]
                                       :organization
                                       req))))
    (testing "an archived policy contributes no capability"
      (is (error/unauthorized? (SUT/check-capability [(with-status
                                                       :policy-status-archived)]
                                                     :organization
                                                     req))))))

(defn- new-policy!
  [config]
  (SUT/new-policy config
                  {:name "Lifecycle"
                   :enabled true
                   :category :policy-category-standard
                   :capabilities []
                   :limits []
                   :labels {}}))

(def ^:private a-bank-target {:kind {:bank {:bank-id "acc.archive-test"}}})

(deftest archive-policy-test
  (with-test-system
   [sys "classpath:policy/application-test.yml"]
   (let [config (fdb-config sys)]
     (testing "new-policy defaults to active status"
       (nom-test> [created (new-policy! config)
                   _ (is (= :policy-status-active (:status created)))]))
     (testing "archiving an unbound policy persists the archived status"
       (nom-test> [created (new-policy! config)
                   policy-id (:policy-id created)
                   archived (SUT/archive-policy config policy-id)
                   _ (is (= :policy-status-archived (:status archived)))
                   loaded (SUT/get-policy config policy-id)
                   _ (is (= :policy-status-archived (:status loaded))
                         "archived status round-trips through the store")]))
     (testing "archiving a bound policy is rejected and leaves it active"
       (let [created (new-policy! config)
             policy-id (:policy-id created)
             _ (SUT/new-binding config
                                {:policy-id policy-id :target a-bank-target})
             result (SUT/archive-policy config policy-id)
             loaded (SUT/get-policy config policy-id)]
         (is (error/anomaly? result))
         (is (= :policy/still-bound (error/kind result)))
         (is (= :policy-status-active (:status loaded))
             "a rejected archive leaves the policy active")))
     (testing "an archived policy cannot gain a new binding"
       (let [created (new-policy! config)
             policy-id (:policy-id created)
             _ (SUT/archive-policy config policy-id)
             result (SUT/new-binding config
                                     {:policy-id policy-id
                                      :target a-bank-target})]
         (is (error/anomaly? result))
         (is (= :policy/archived (error/kind result))))))))

(deftest remove-binding-test
  (with-test-system
   [sys "classpath:policy/application-test.yml"]
   (let [config (fdb-config sys)]
     (testing "removing an existing binding returns it and it is gone"
       (nom-test> [created (new-policy! config)
                   policy-id (:policy-id created)
                   binding (SUT/new-binding config
                                            {:policy-id policy-id
                                             :target a-bank-target})
                   binding-id (:binding-id binding)
                   removed (SUT/remove-binding config binding-id)
                   _ (is (= binding-id (:binding-id removed)))
                   _ (let [after (SUT/get-binding config binding-id)]
                       (is (error/anomaly? after))
                       (is (= :policy-binding/not-found (error/kind after))))]))
     (testing "removing a missing binding is rejected"
       (let [result (SUT/remove-binding config "bnd.does-not-exist")]
         (is (error/anomaly? result))
         (is (= :policy-binding/not-found (error/kind result)))))
     (testing "unbinding lets a previously-bound policy be archived"
       (nom-test> [created (new-policy! config)
                   policy-id (:policy-id created)
                   binding (SUT/new-binding config
                                            {:policy-id policy-id
                                             :target a-bank-target})
                   _ (let [blocked (SUT/archive-policy config policy-id)]
                       (is (error/anomaly? blocked))
                       (is (= :policy/still-bound (error/kind blocked))
                           "bound policy can't be archived"))
                   _ (SUT/remove-binding config (:binding-id binding))
                   archived (SUT/archive-policy config policy-id)
                   _ (is (= :policy-status-archived (:status archived))
                         "archives once unbound")])))))
