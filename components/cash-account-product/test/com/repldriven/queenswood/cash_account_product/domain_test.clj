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
   :allowed-currencies ["GBP"]
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

(def ^:private discarded-version
  (assoc published-version
         :version-id "prv.2"
         :version-number 2
         :status :version-status-discarded))

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
  (testing "rejects with :version-immutable when existing is discarded"
    (let [r (SUT/update-version discarded-version
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

(deftest publish-test
  (testing "rejects with :version-immutable when already published"
    (let [r (SUT/publish published-version
                         permissive-policies
                         payment-provider
                         operator)]
      (is (error/rejection? r))
      (is (= :cash-account-product/version-immutable (error/kind r)))))
  (testing "rejects with :version-immutable when discarded"
    (let [r (SUT/publish discarded-version
                         permissive-policies
                         payment-provider
                         operator)]
      (is (error/rejection? r))
      (is (= :cash-account-product/version-immutable (error/kind r)))))
  (testing "flips draft to :published, preserving other fields"
    (let [v (SUT/publish draft-version
                         permissive-policies
                         payment-provider
                         operator)]
      (is (= :version-status-published (:status v)))
      (is (number? (:updated-at v)))
      (is (= operator (:updated-by v)))
      (is (= "prv.3" (:version-id v)))
      (is (= 3 (:version-number v)))))
  (testing "rejects an address scheme the payment provider does not issue"
    (let [r (SUT/publish (assoc draft-version
                                :allowed-payment-address-schemes
                                [:payment-address-scheme-scan
                                 :payment-address-scheme-iban])
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
                         permissive-policies
                         payment-provider
                         operator)]
      (is (= :version-status-published (:status v))))))

(deftest discard-test
  (testing "rejects with :version-immutable when already published"
    (let [r (SUT/discard published-version permissive-policies operator)]
      (is (error/rejection? r))
      (is (= :cash-account-product/version-immutable (error/kind r)))))
  (testing "rejects with :version-immutable when already discarded"
    (let [r (SUT/discard discarded-version permissive-policies operator)]
      (is (error/rejection? r))
      (is (= :cash-account-product/version-immutable (error/kind r)))))
  (testing "flips draft to :discarded, by whom recorded"
    (let [v (SUT/discard draft-version permissive-policies operator)]
      (is (= :version-status-discarded (:status v)))
      (is (= operator (:updated-by v))))))

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
  (testing "succeeds when prior versions are all published or discarded"
    (let [v (SUT/new-version "bnk.1"
                             "prd.1"
                             [published-version discarded-version]
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
        terms {:basis :interest-schedule-basis-relative
               :banding :interest-banding-marginal
               :steps [{:bands [{:up-to 500000 :rate-bps 500} {:rate-bps 0}]}
                       {:starts-after-months 12 :bands [{:rate-bps 150}]}]
               :day-count :interest-day-count-actual-365
               :payment {:frequency :interest-payment-frequency-monthly
                         :day :interest-payment-day-account}}
        refused (fn [data]
                  (let [r (draft data)]
                    (when (and (error/rejection? r)
                               (= :cash-account-product/invalid-interest-terms
                                  (error/kind r)))
                      (:message (error/payload r)))))]
    (testing "a version keeps the terms it is drafted with"
      (is (= terms (:interest-terms (draft {:interest-terms terms})))))
    (testing "a rate alone is still one band paid daily"
      (is (= [{:bands [{:rate-bps 250}]}]
             (get-in (draft {:interest-rate-bps 250})
                     [:interest-terms :steps]))))
    (testing "terms and a rate together are refused"
      (is (some? (refused {:interest-terms terms :interest-rate-bps 250}))))
    (testing "each rule of the schedule is refused with its message"
      (doseq [[change message]
              [[[:steps] "Interest terms need at least one step"]
               [[:steps 0 :starts-after-months] "The first step has no start"]
               [[:steps 1 :starts-after-months]
                "Every later step starts as the schedule's basis says"]
               [[:steps 1 :bands] "Every step needs at least one band"]
               [[:steps 0 :bands 1 :up-to]
                "A step's last band has no upper limit"]
               [[:steps 0 :bands 0 :rate-bps] "A rate cannot be negative"]]]
        (let [value (case (peek change)
                      :steps []
                      :bands []
                      :starts-after-months (if (= 0 (second change)) 3 nil)
                      :up-to 900000
                      :rate-bps -1)
              changed (if (nil? value)
                        (update-in terms (pop change) dissoc (peek change))
                        (assoc-in terms change value))]
          (is (= message (refused {:interest-terms changed}))
              (pr-str change)))))
    (testing "bands rise from zero, and steps start one after another"
      (is (= "Each band's upper limit is above zero and above the one before"
             (refused {:interest-terms (assoc-in terms
                                        [:steps 0 :bands]
                                        [{:up-to 500000 :rate-bps 500}
                                         {:up-to 400000 :rate-bps 300}
                                         {:rate-bps 0}])})))
      (is (= "Each step starts after the one before"
             (refused {:interest-terms (update terms
                                               :steps
                                               conj
                                               {:starts-after-months 6
                                                :bands [{:rate-bps 100}]})}))))
    (testing "a fixed schedule's later steps start on a date"
      (let [fixed (assoc terms
                         :basis :interest-schedule-basis-fixed
                         :steps [{:bands [{:rate-bps 500}]}
                                 {:starts-on 20800 :bands [{:rate-bps 150}]}])]
        (is (= fixed (:interest-terms (draft {:interest-terms fixed}))))
        (is (= "Every later step starts as the schedule's basis says"
               (refused
                {:interest-terms
                 (assoc fixed :basis :interest-schedule-basis-relative)})))))
    (testing "a payment names the day its frequency needs, and no more"
      (doseq [payment [{:frequency :interest-payment-frequency-daily
                        :day :interest-payment-day-account}
                       {:frequency :interest-payment-frequency-monthly}
                       {:frequency :interest-payment-frequency-monthly
                        :day :interest-payment-day-day-of-month}
                       {:frequency :interest-payment-frequency-monthly
                        :day :interest-payment-day-account
                        :day-of-month 5}
                       {:frequency :interest-payment-frequency-annually
                        :day :interest-payment-day-last-of-month}
                       {:frequency :interest-payment-frequency-monthly
                        :day :interest-payment-day-last-of-month
                        :month 3}]]
        (is (some? (refused {:interest-terms (assoc terms :payment payment)}))
            (pr-str payment)))
      (doseq [payment [{:frequency :interest-payment-frequency-daily}
                       {:frequency :interest-payment-frequency-at-close}
                       {:frequency :interest-payment-frequency-monthly
                        :day :interest-payment-day-day-of-month
                        :day-of-month 28}
                       {:frequency :interest-payment-frequency-quarterly
                        :day :interest-payment-day-last-of-month
                        :month 3}
                       {:frequency :interest-payment-frequency-annually
                        :day :interest-payment-day-account}]]
        (is (nil? (refused {:interest-terms (assoc terms :payment payment)}))
            (pr-str payment))))))

