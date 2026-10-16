(ns com.repldriven.queenswood.cash-account-product.domain-test
  "Pure-function tests for the rejection paths surfaced by
  cash-account-product domain operations: `:version-immutable`
  on update/publish/discard against non-draft versions,
  `:draft-already-exists` on new-version when a draft is open, and
  `:invalid-reward` on a reward of nothing; and for the opening reward
  threading through create, new version and update, or staying off a
  version that never named one.

  The template is the resolved record core looks up by id and passes
  in; here it's a fixture map.

  These kinds are asserted here rather than in `interface-test`, which
  holds what only a record store can show — the idempotency-key index,
  the count indexes, the template store and the concurrent-create
  race."
  (:require
    [com.repldriven.queenswood.cash-account-product.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private permissive-policies
  "Single allow-everything policy — the domain functions check
  capability before some rejections fire, so tests that need to
  reach a downstream check pass this in. The `:kind` is a oneof
  map (variant → fields), and an empty fields map matches every
  request because the matcher only constrains on set fields."
  [{:status :policy-status-active
    :capabilities [{:kind {:cash-account-product {}} :effect :effect-allow}]}])

(def ^:private template
  {:template-id "tpl.00000000000000000000000001"
   :product-type :account-product-type-sub-ledger-current
   :balance-sheet-side :balance-sheet-side-liability
   :currencies [{:currency "GBP"
                 :day-count :interest-day-count-actual-365
                 :business-day-convention
                 :business-day-convention-modified-following
                 :calendars [:business-day-calendar-weekends
                             :business-day-calendar-gblo]}]
   :balance-products [{:balance-type :balance-type-default
                       :balance-status :balance-status-posted}]
   :allowed-payment-address-schemes [:payment-address-scheme-scan]
   :iso-cash-account-type :iso-cash-account-type-cacc})

(def ^:private published-version
  {:bank-id "bnk.1"
   :product-id "prd.1"
   :version-id "prv.1"
   :version-number 1
   :status :version-status-published
   :name "Published"
   :currency "GBP"
   :product-type :account-product-type-sub-ledger-current
   :template-id "tpl.00000000000000000000000001"
   :balance-sheet-side :balance-sheet-side-liability
   :balance-products [{:balance-type :balance-type-default
                       :balance-status :balance-status-posted}]
   :effective-from 20089
   :created-at 1700000000000
   :updated-at 1700000000000})

(def ^:private draft-version
  (assoc published-version
         :version-id "prv.3"
         :version-number 3
         :status :version-status-draft))

(def ^:private good-data {:name "v2" :currency "GBP" :effective-from 20089})

(def ^:private operator
  {:kind :actor-kind-operator :principal-id "queenswood-admin"})

(deftest update-version-test
  (testing "rejects with :version-immutable when existing is published"
    (let [r (SUT/update-version published-version
                                template
                                good-data
                                permissive-policies
                                operator)]
      (is (error/rejection? r))
      (is (= :cash-account-product/version-immutable (error/kind r)))))
  (testing "succeeds when existing is a draft, preserving id and number"
    (let [v (SUT/update-version draft-version
                                template
                                (assoc good-data :name "renamed")
                                permissive-policies
                                operator)]
      (is (= "prv.3" (:version-id v)))
      (is (= 3 (:version-number v)))
      (is (= "renamed" (:name v)))
      (is (= :version-status-draft (:status v))))))

(def ^:private payment-provider {:addresses ["scan"]})

(defn- publishes
  [n]
  {:cash-account-product {#{:bank-id :product-id :published-on} n}})

(deftest publish-test
  (testing "rejects with :version-immutable when already published"
    (let [r (SUT/publish published-version
                         20500
                         (publishes 0)
                         permissive-policies
                         payment-provider
                         operator)]
      (is (error/rejection? r))
      (is (= :cash-account-product/version-immutable (error/kind r)))))
  (testing "flips draft to :published, preserving other fields"
    (let [v (SUT/publish draft-version
                         20500
                         (publishes 0)
                         permissive-policies
                         payment-provider
                         operator)]
      (is (= :version-status-published (:status v)))
      (is (= 20500 (:published-on v)))
      (is (number? (:updated-at v)))
      (is (= operator (:updated-by v)))
      (is (= "prv.3" (:version-id v)))
      (is (= 3 (:version-number v)))))
  (testing "rejects an address scheme the payment provider does not issue"
    (let [r (SUT/publish (assoc draft-version
                                :allowed-payment-address-schemes
                                [:payment-address-scheme-scan
                                 :payment-address-scheme-iban])
                         20500
                         (publishes 0)
                         permissive-policies
                         payment-provider
                         operator)]
      (is (error/rejection? r))
      (is (= :cash-account-product/unsupported-address-scheme (error/kind r)))
      (is (= [:payment-address-scheme-iban] (:unsupported (error/payload r))))))
  (testing "publishes a scheme the provider issues"
    (let [v (SUT/publish (assoc draft-version
                                :allowed-payment-address-schemes
                                [:payment-address-scheme-scan])
                         20500
                         (publishes 0)
                         permissive-policies
                         payment-provider
                         operator)]
      (is (= :version-status-published (:status v))))))

(deftest discard-test
  (testing "rejects with :version-immutable when already published"
    (let [r (SUT/discard published-version permissive-policies operator)]
      (is (error/rejection? r))
      (is (= :cash-account-product/version-immutable (error/kind r)))))
  (testing "answers a draft as it is, for deleting"
    (is (= draft-version
           (SUT/discard draft-version permissive-policies operator)))))

(deftest new-version-test
  (testing "rejects with :draft-already-exists when versions contains a draft"
    (let [r (SUT/new-version "bnk.1"
                             "prd.1"
                             [draft-version]
                             template
                             good-data
                             permissive-policies)]
      (is (error/rejection? r))
      (is (= :cash-account-product/draft-already-exists (error/kind r)))))
  (testing "succeeds when prior versions are all published"
    (let [v (SUT/new-version "bnk.1"
                             "prd.1"
                             [published-version published-version]
                             template
                             good-data
                             permissive-policies)]
      (is (= :version-status-draft (:status v)))
      (is (= 3 (:version-number v)) "version-number is 1 + (count versions)")))
  (testing "succeeds with no prior versions — fresh product flow"
    (let [v (SUT/new-version "bnk.1"
                             "prd.1"
                             []
                             template
                             good-data
                             permissive-policies)]
      (is (= 1 (:version-number v)))
      (is (= :version-status-draft (:status v)))))
  (testing "snapshots the derived fields from the template"
    (let [v (SUT/new-version "bnk.1"
                             "prd.1"
                             []
                             template
                             good-data
                             permissive-policies)]
      (is (= "tpl.00000000000000000000000001" (:template-id v)))
      (is (= :account-product-type-sub-ledger-current (:product-type v)))
      (is (= :balance-sheet-side-liability (:balance-sheet-side v)))
      (is (= :iso-cash-account-type-cacc (:iso-cash-account-type v)))
      (is (= "GBP" (:currency v)))
      (is (= [:payment-address-scheme-scan]
             (:allowed-payment-address-schemes v)))
      (is (some #(= %
                    {:balance-type :balance-type-default
                     :balance-status :balance-status-posted})
                (:balance-products v))
          "template provides at least the default-posted balance bucket")))
  (testing
    "rejects with :currency-not-allowed when currency is outside the menu"
    (let [r (SUT/new-version "bnk.1"
                             "prd.1"
                             []
                             template
                             (assoc good-data :currency "USD")
                             permissive-policies)]
      (is (error/rejection? r))
      (is (= :cash-account-product/currency-not-allowed (error/kind r))))))

(deftest effective-window-test
  (testing "a product without effective-from is rejected"
    (let [r (SUT/new-version "bnk.1"
                             "prd.1"
                             []
                             template
                             (dissoc good-data :effective-from)
                             permissive-policies)]
      (is (error/rejection? r))
      (is (= :cash-account-product/effective-from-required (error/kind r)))))
  (testing "effective-to not strictly after effective-from is rejected"
    (let [r (SUT/new-version
             "bnk.1"
             "prd.1"
             []
             template
             (assoc good-data :effective-from 20089 :effective-to 20089)
             permissive-policies)]
      (is (error/rejection? r))
      (is (= :cash-account-product/invalid-effective-window (error/kind r))))))

(deftest opening-reward-test
  (testing "a new version carries the reward it was given"
    (let [v (SUT/new-version "bnk.1"
                             "prd.1"
                             []
                             template
                             (assoc good-data :opening-reward {:amount 1000})
                             permissive-policies)]
      (is (= [{:kind :reward-kind-opening :amount 1000}] (:reward-terms v)))))
  (testing "a version that names no reward carries none"
    (let [v (SUT/new-version "bnk.1"
                             "prd.1"
                             []
                             template
                             good-data
                             permissive-policies)]
      (is (not (contains? v :reward-terms)))))
  (testing "a draft's update replaces or removes it"
    (let [with-reward (SUT/update-version
                       draft-version
                       template
                       (assoc good-data :opening-reward {:amount 2500})
                       permissive-policies
                       operator)
          without (SUT/update-version draft-version
                                      template
                                      good-data
                                      permissive-policies
                                      operator)]
      (is (= [{:kind :reward-kind-opening :amount 2500}]
             (:reward-terms with-reward)))
      (is (not (contains? without :reward-terms)))))
  (testing "a reward of nothing, or less, is rejected with :invalid-reward"
    (doseq [amount [0 -1 nil]]
      (let [r (SUT/new-version
               "bnk.1"
               "prd.1"
               []
               template
               (assoc good-data :opening-reward {:amount amount})
               permissive-policies)]
        (is (error/rejection? r))
        (is (= :cash-account-product/invalid-reward (error/kind r))))))
  (testing "a published version's reward cannot change"
    (let [r (SUT/update-version published-version
                                template
                                (assoc good-data :opening-reward {:amount 1000})
                                permissive-policies
                                operator)]
      (is (error/rejection? r))
      (is (= :cash-account-product/version-immutable (error/kind r))))))

(deftest payment-limits-test
  (let [draft (fn [payment-limits]
                (SUT/new-version
                 "bnk.1"
                 "prd.1"
                 []
                 template
                 (assoc good-data :payment-limits payment-limits)
                 permissive-policies))
        paid-in {:direction :payment-direction-in
                 :measure :amount
                 :max 30000
                 :period :time-window-monthly}
        withdrawals {:direction :payment-direction-out
                     :measure :count
                     :max 1
                     :period :time-window-annually
                     :anchor :window-anchor-calendar
                     :starts-month 4
                     :starts-day-of-month 6
                     :reason "One withdrawal each tax year"}]
    (testing "each payment limit becomes an account-payment policy limit"
      (is
       (= [{:kind {:account-payment {:filters [{:direction
                                                :payment-direction-in}]}}
            :bound {:kind {:max {:aggregate
                                 {:kind {:amount
                                         {:value {:value 30000 :currency "GBP"}
                                          :window :time-window-monthly}}}}}}
            :reason (str "The product allows at most 30000 GBP minor units"
                         " paid in per month")}
           {:kind {:account-payment {:filters [{:direction
                                                :payment-direction-out}]}}
            :bound {:kind {:max {:aggregate
                                 {:kind {:count {:value 1
                                                 :window :time-window-annually
                                                 :anchor :window-anchor-calendar
                                                 :starts-month 4
                                                 :starts-day-of-month 6}}}}}}
            :reason "One withdrawal each tax year"}]
          (filterv (fn [limit] (get-in limit [:kind :account-payment]))
                   (:limits (draft [paid-in withdrawals]))))))
    (testing "a version stated none carries none"
      (is (not-any? (fn [limit] (get-in limit [:kind :account-payment]))
                    (:limits (draft nil))))
      (is (not-any? (fn [limit] (get-in limit [:kind :account-payment]))
                    (:limits (draft [])))))
    (testing "a limit that bounds nothing, or twice, is :invalid-limit"
      (doseq [payment-limits [[(assoc paid-in :max -1)]
                              [(assoc withdrawals :period :time-window-instant)]
                              [(assoc paid-in :starts-month 4)]
                              [paid-in (assoc paid-in :max 50000)]]]
        (let [r (draft payment-limits)]
          (is (error/rejection? r) (pr-str payment-limits))
          (is (= :cash-account-product/invalid-limit (error/kind r))))))))

(deftest balance-limits-test
  (let [draft (fn [data]
                (SUT/new-version "bnk.1"
                                 "prd.1"
                                 []
                                 template
                                 (merge good-data data)
                                 permissive-policies))
        bound (fn [version side]
                (some (fn [limit]
                        (when (get-in limit [:kind :balance])
                          (get-in limit
                                  [:bound :kind side :aggregate :kind :amount
                                   :value])))
                      (:limits version)))
        types (fn [version side]
                (some (fn [limit]
                        (when (get-in limit [:bound :kind side])
                          (mapv :transaction-type
                                (get-in limit [:kind :balance :filters]))))
                      (:limits version)))]
    (testing "a version that states no minimum cannot go below zero"
      (let [version (draft {})]
        (is (= {:value 0 :currency "GBP"} (bound version :min)))
        (is (nil? (bound version :max)))))
    (testing "a stated minimum and maximum bound the available balance"
      (let [version (draft {:min-balance -50000 :max-balance 2000000})]
        (is (= {:value -50000 :currency "GBP"} (bound version :min)))
        (is (= {:value 2000000 :currency "GBP"} (bound version :max)))
        (is (every? (fn [limit] (= :limit-allow-improving (:allow limit)))
                    (filter (fn [limit] (get-in limit [:kind :balance]))
                            (:limits version))))))
    (testing
      "the minimum bounds payments and rewards, the maximum payments in,
              and neither interest"
      (let [version (draft {:max-balance 2000000})]
        (is (= [:transaction-type-internal-transfer
                :transaction-type-inbound-transfer
                :transaction-type-outbound-transfer :transaction-type-reward]
               (types version :min)))
        (is (= [:transaction-type-internal-transfer
                :transaction-type-inbound-transfer]
               (types version :max)))))
    (testing "a maximum at or below the minimum is :invalid-limit"
      (doseq [data [{:max-balance 0} {:min-balance 10000 :max-balance 10000}
                    {:min-balance 10000 :max-balance 5000}]]
        (let [r (draft data)]
          (is (error/rejection? r) (pr-str data))
          (is (= :cash-account-product/invalid-limit (error/kind r))))))))

(deftest interest-terms-test
  (let [draft (fn [data]
                (SUT/new-version "bnk.1"
                                 "prd.1"
                                 []
                                 template
                                 (merge (dissoc good-data :interest-rate-bps)
                                        data)
                                 permissive-policies))
        terms {:accrual {:banding :interest-banding-marginal
                         :bands [0 500000]
                         :schedule {:relative {:unit :interest-period-unit-month
                                               :periods
                                               [{:after 0 :rates-bps [500 0]}
                                                {:after 12
                                                 :rates-bps [150 150]}]}}}
               :payment {:kind {:account-day {:unit :interest-period-unit-month
                                              :every 1}}}}
        conventions
        (fn [terms]
          (-> terms
              (assoc-in [:accrual :day-count] :interest-day-count-actual-365)
              (assoc-in [:payment :business-day-convention]
                        :business-day-convention-modified-following)
              (update-in [:payment :calendar]
                         (fnil identity :business-day-calendar-none))))
        refused (fn [data]
                  (let [r (draft data)]
                    (when (and (error/rejection? r)
                               (= :cash-account-product/invalid-interest-terms
                                  (error/kind r)))
                      (:message (error/payload r)))))
        periods [:accrual :schedule :relative :periods]]
    (testing "a version keeps its terms, with its currency's conventions"
      (is (= (conventions terms)
             (:interest-terms (draft {:interest-terms terms})))))
    (testing "a payment's calendar is its own, or every day"
      (let [calendar (fn [payment]
                       (get-in (draft {:interest-terms
                                       (assoc terms :payment payment)})
                               [:interest-terms :payment :calendar]))]
        (is (= :business-day-calendar-gblo
               (calendar (assoc (:payment terms)
                                :calendar
                                :business-day-calendar-gblo))))
        (is (= :business-day-calendar-none (calendar (:payment terms))))
        (testing "but not one its currency does not offer"
          (is (= "The currency offers no such calendar"
                 (refused {:interest-terms (assoc-in terms
                                            [:payment :calendar]
                                            :business-day-calendar-euta)}))))
        (testing "and every day at close, which has no schedule"
          (is (= :business-day-calendar-none
                 (calendar {:kind {:closure-day {}}
                            :calendar :business-day-calendar-gblo}))))))
    (testing "a rate alone is one band from opening, paid daily"
      (let [read (:interest-terms (draft {:interest-rate-bps 250}))]
        (is (= {:bands [0]
                :schedule {:relative {:unit :interest-period-unit-month
                                      :periods [{:after 0 :rates-bps [250]}]}}}
               (select-keys (:accrual read) [:bands :schedule])))
        (is (= {:account-day {:unit :interest-period-unit-day :every 1}}
               (get-in read [:payment :kind])))))
    (testing "terms and a rate together are refused"
      (is (= "State interest-terms or interest-rate-bps, not both"
             (refused {:interest-terms terms :interest-rate-bps 250}))))
    (testing "the grid's three rules are refused with their messages"
      (doseq [[path value message]
              [[[:accrual :bands] []
                "Bands start at zero, each above the one before"]
               [[:accrual :bands] [100 500000]
                "Bands start at zero, each above the one before"]
               [[:accrual :bands] [0 500000 500000]
                "Bands start at zero, each above the one before"]
               [periods []
                "Periods start 0 after opening, each after the one before"]
               [(conj periods 0 :after) 1
                "Periods start 0 after opening, each after the one before"]
               [(conj periods 1 :after) 0
                "Periods start 0 after opening, each after the one before"]
               [(conj periods 1 :rates-bps) [150]
                "Every period has one rate for each band"]]]
        (is (= message (refused {:interest-terms (assoc-in terms path value)}))
            (pr-str path value))))
    (testing "a fixed schedule starts on the version's effective-from"
      (let [fixed (assoc-in terms
                   [:accrual :schedule]
                   {:fixed {:periods [{:from-on 20089 :rates-bps [500 0]}
                                      {:from-on 20800 :rates-bps [150 150]}]}})]
        (is (= (conventions fixed)
               (:interest-terms (draft {:interest-terms fixed}))))
        (is
         (=
          "Periods start on the version's effective-from, each after the one before"
          (refused {:interest-terms (assoc-in fixed
                                     [:accrual :schedule :fixed :periods 0
                                      :from-on]
                                     20100)})))))
    (testing "each kind of payment is kept"
      (doseq [kind [{:calendar-day {:every 3 :day-of-month 31 :in-month 3}}
                    {:closure-day {}}]]
        (is (= kind
               (get-in (draft {:interest-terms
                               (assoc-in terms [:payment :kind] kind)})
                       [:interest-terms :payment :kind]))
            (pr-str kind))))
    (testing "a currency's conventions replace any the caller states"
      (is (= (conventions terms)
             (:interest-terms (draft
                               {:interest-terms
                                (assoc-in terms
                                 [:accrual :day-count]
                                 :interest-day-count-actual-actual)})))))))

(deftest closure-terms-test
  (let [draft (fn [data]
                (SUT/new-version "bnk.1"
                                 "prd.1"
                                 []
                                 template
                                 (merge good-data data)
                                 permissive-policies))
        refused (fn [data]
                  (let [r (draft data)]
                    (when (and (error/rejection? r)
                               (= :cash-account-product/invalid-closure-terms
                                  (error/kind r)))
                      (:message (error/payload r)))))
        pay {:kind {:pay {}}}
        forfeit {:kind {:forfeit {}}}
        window (fn [count kind]
                 {:unit :interest-period-unit-month :count count :kind kind})
        stepped {:interest pay
                 :early [(window 3 {:refused {}})
                         (window 12
                                 {:charged
                                  {:interest {:kind {:forfeit-days {:days 90}}}
                                   :fee {:kind {:balance {:rate-bps 100
                                                          :min 500
                                                          :max 5000}}}}})]}
        closure-day (fn [data]
                      (merge {:interest-terms
                              {:accrual {:banding :interest-banding-marginal
                                         :bands [0]
                                         :schedule
                                         {:relative
                                          {:unit :interest-period-unit-month
                                           :periods [{:after 0
                                                      :rates-bps [450]}]}}}
                               :payment {:kind {:closure-day {}}}}}
                             (dissoc data :interest-rate-bps)))]
    (testing "a version stating none pays what has accrued and charges nothing"
      (is (= {:interest pay} (:closure-terms (draft {}))))
      (is (= {:interest pay} (:closure-terms (draft {:interest-rate-bps 0})))))
    (testing "a version keeps the terms it states"
      (is (= stepped
             (:closure-terms (draft {:interest-rate-bps 250
                                     :closure-terms stepped})))))
    (testing "only a version that pays interest can forfeit it"
      (is (= "Only a version that pays interest can forfeit it at closure"
             (refused {:interest-rate-bps 0
                       :closure-terms {:interest forfeit}})))
      (is (= {:interest pay :early [(window 6 {:refused {}})]}
             (:closure-terms (draft {:interest-rate-bps 0
                                     :closure-terms
                                     {:interest pay
                                      :early [(window 6 {:refused {}})]}})))
          "though it may refuse an early closure"))
    (testing "a version paid on its closure day pays some of it"
      (is
       (=
        "A version paying interest on its closure day cannot forfeit all of it"
        (refused (closure-day {:closure-terms {:interest forfeit}}))))
      (is
       (=
        "A version paying interest on its closure day cannot forfeit all of it"
        (refused (closure-day
                  {:closure-terms
                   {:interest pay
                    :early [(window 12 {:charged {:interest forfeit}})]}}))))
      (is (some? (:closure-terms (draft (closure-day
                                         {:closure-terms
                                          {:interest {:kind {:forfeit-days
                                                             {:days 90}}}}}))))
          "forfeiting some days pays the rest"))
    (testing "windows start in ascending order, in one unit"
      (is (= "Early closure windows start in ascending order"
             (refused {:closure-terms {:interest pay
                                       :early [(window 12 {:refused {}})
                                               (window 3 {:refused {}})]}})))
      (is (= "Early closure windows count in one unit"
             (refused {:closure-terms {:interest pay
                                       :early
                                       [(window 3 {:refused {}})
                                        (assoc (window 12 {:refused {}})
                                               :unit
                                               :interest-period-unit-day)]}}))))
    (testing "a balance fee's minimum is no more than its maximum"
      (is (= "A balance fee's minimum is no more than its maximum"
             (refused {:closure-terms
                       {:interest pay
                        :early [(window 12
                                        {:charged
                                         {:interest pay
                                          :fee {:kind {:balance
                                                       {:rate-bps 100
                                                        :min 5000
                                                        :max 500}}}}})]}}))))))

(deftest own-funds-product-test
  (let [own-funds (assoc template
                         :template-id "tpl.00000000000000000000000004"
                         :product-type
                         :account-product-type-sub-ledger-own-funds)
        deny-own-funds
        {:status :policy-status-active
         :capabilities
         [{:kind {:cash-account-product
                  {:action :cash-account-product-action-draft
                   :filters (mapv (fn [kind]
                                    {:product-type
                                     :account-product-type-sub-ledger-own-funds
                                     :created-by-kind kind})
                                  [:actor-kind-member
                                   :actor-kind-operator
                                   :actor-kind-bank])}}
           :effect :effect-deny}]}
        policies (conj permissive-policies deny-own-funds)
        aggregates {:cash-account-product {#{:bank-id} 0
                                           #{:bank-id :product-type} 0}}
        create (fn [tpl actor]
                 (SUT/new-product "bnk.1"
                                  tpl
                                  (assoc good-data :created-by actor)
                                  aggregates
                                  policies))]
    (testing "a member, an operator or a bank's credential is denied one"
      (doseq [actor [{:kind :actor-kind-member :principal-id "usr.1"} operator
                     {:kind :actor-kind-bank :principal-id "bnk.1"}]]
        (let [r (create own-funds actor)]
          (is (error/unauthorized? r) (pr-str actor))
          (is (= :policy/denied (error/kind r)) (pr-str actor)))))
    (testing "the platform creates it"
      (is (= :account-product-type-sub-ledger-own-funds
             (:product-type (create own-funds
                                    {:kind :actor-kind-platform
                                     :principal-id "bank"})))))
    (testing "the deny names own funds alone"
      (is (= :account-product-type-sub-ledger-current
             (:product-type (create template operator)))))))

(def ^:private two-publishes-a-month
  (conj permissive-policies
        {:status :policy-status-active
         :limits
         [{:kind {:cash-account-product
                  {:filters [{:action :cash-account-product-action-publish}]}}
           :bound {:kind {:max {:aggregate
                                {:kind {:count {:value 2
                                                :window
                                                :time-window-monthly}}}}}}}]}))

(deftest a-product-publishes-within-its-month-test
  (testing "a publish within the month's is allowed"
    (is (= :version-status-published
           (:status (SUT/publish draft-version
                                 20500
                                 (publishes 1)
                                 two-publishes-a-month
                                 payment-provider
                                 operator)))))
  (testing "one past them is refused"
    (is (= :policy/limit-exceeded
           (error/kind (SUT/publish draft-version
                                    20500
                                    (publishes 2)
                                    two-publishes-a-month
                                    payment-provider
                                    operator))))))
