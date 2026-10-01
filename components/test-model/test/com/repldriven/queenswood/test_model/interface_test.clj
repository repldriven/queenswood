(ns com.repldriven.queenswood.test-model.interface-test
  (:require
    [com.repldriven.queenswood.test-model.interface :as SUT]

    [clojure.test :refer [deftest is testing]]))

;; A platform policy in the shapes the rig's carries: the available
;; balance kept non-negative unless a move improves it, the capabilities
;; the commands need, and one interest run of each kind per date.
(def ^:private platform
  {:name "Platform policy"
   :capabilities (mapv (fn [[kind action]]
                         {:effect :effect-allow :kind {kind {:action action}}})
                       [[:cash-account :cash-account-action-open]
                        [:cash-account :cash-account-action-close]
                        [:inbound-payment :inbound-payment-action-receive]
                        [:outbound-payment :outbound-payment-action-send]
                        [:internal-payment :internal-payment-action-submit]
                        [:interest :interest-action-accrue]
                        [:interest :interest-action-capitalize]])
   :limits
   [{:kind {:balance {:filters [{:kind {:computed {:name "available"}}
                                 :transaction-type
                                 :transaction-type-inbound-transfer}]}}
     :bound {:kind {:min {:aggregate {:kind {:amount
                                             {:value {:value 0 :currency "GBP"}
                                              :window :time-window-instant}}}}}}
     :allow :limit-allow-improving}
    {:kind {:interest {:filters [{:action :interest-action-accrue}]}}
     :bound {:kind {:max {:aggregate
                          {:kind {:count {:value 1
                                          :window :time-window-daily}}}}}}}]})

(def ^:private tier {:name "Test-scenario policy"})

(def ^:private init
  (SUT/with-policies SUT/init-state {:platform platform :tier tier}))

(defn- step
  "Applies the named command's `:next-state` with the given args
  vector to `state`. Mirrors how the runner threads commands."
  [state command args]
  (let [spec (get SUT/model command)]
    ((:next-state spec) state {:args args})))

(defn- version
  "The version map every product verb's payload produces, so a test
  asserts on status and number without restating the currency and
  effective window each one carries."
  [status number]
  {:status status
   :number number
   :currency "GBP"
   :effective-from 20089
   :effective-to nil})

(deftest create-bank-test
  (testing
    "create-bank allocates a bank, settlement product, org-party, and settlement account"
    (let [s (step init :create-bank [])]
      (is (= [:acct-0] (SUT/known-accounts s)))
      (is (= [:bank-0] (keys (:banks s))))
      (is (= [:prod-0] (keys (:products s))))
      (is (= [:party-0] (keys (:parties s))))
      (is (= 0 (SUT/balance s :acct-0)))
      (is (= :bank-0 (get-in s [:accounts :acct-0 :bank])))
      (is (= :prod-0 (get-in s [:accounts :acct-0 :product])))
      (is (= :party-0 (get-in s [:accounts :acct-0 :party])))
      (is (= [:acct-0] (get-in s [:banks :bank-0 :accounts])))
      (is (= [:prod-0] (get-in s [:banks :bank-0 :products])))
      (is (= [:party-0] (get-in s [:banks :bank-0 :parties])))
      (is (= [(version :published 1)] (get-in s [:products :prod-0 :versions])))
      (is (= :active (get-in s [:parties :party-0 :status])))
      (is (= :organization (get-in s [:parties :party-0 :type])))
      (is (= 1 (:next-id s)))
      (is (= 1 (:next-bank-id s)))
      (is (= 1 (:next-product-id s)))
      (is (= 1 (:next-party-id s)))))
  (testing
    "successive create-bank calls make distinct banks, products, and parties"
    (let [s (-> init
                (step :create-bank [])
                (step :create-bank []))]
      (is (= #{:acct-0 :acct-1} (set (SUT/known-accounts s))))
      (is (= #{:bank-0 :bank-1} (set (keys (:banks s)))))
      (is (= #{:prod-0 :prod-1} (set (keys (:products s)))))
      (is (= #{:party-0 :party-1} (set (keys (:parties s)))))
      (is (= :prod-0 (get-in s [:accounts :acct-0 :product])))
      (is (= :prod-1 (get-in s [:accounts :acct-1 :product])))
      (is (= :party-0 (get-in s [:accounts :acct-0 :party])))
      (is (= :party-1 (get-in s [:accounts :acct-1 :party]))))))

(deftest create-and-publish-product-test
  (let [s0 (step init :create-bank [])]
    (testing "create-product opens v1 as draft, attached to an org"
      (let [s1 (step s0 :create-product [:bank-0 :current 0])]
        (is (= 1 (:next-product-id s0))
            "prod-0 was already taken by the auto settlement product")
        (is (= [(version :draft 1)] (get-in s1 [:products :prod-1 :versions])))
        (is (= :bank-0 (get-in s1 [:products :prod-1 :bank])))
        (is (= :current (get-in s1 [:products :prod-1 :product-type])))
        (is (= [:prod-0 :prod-1] (get-in s1 [:banks :bank-0 :products])))))
    (testing "create-product :savings carries the rate-bps"
      (let [s1 (step s0 :create-product [:bank-0 :savings 250])]
        (is (= :savings (get-in s1 [:products :prod-1 :product-type])))
        (is (= 250 (get-in s1 [:products :prod-1 :interest-rate-bps])))))
    (testing "publish-product flips the latest draft to published"
      (let [s2 (-> s0
                   (step :create-product [:bank-0 :current 0])
                   (step :publish-product [:prod-1]))]
        (is (= [(version :published 1)]
               (get-in s2 [:products :prod-1 :versions])))))
    (testing "open-draft after publish appends v2 in :draft"
      (let [s5 (-> s0
                   (step :create-product [:bank-0 :current 0])
                   (step :publish-product [:prod-1])
                   (step :open-draft [:prod-1]))]
        (is (= [(version :published 1) (version :draft 2)]
               (get-in s5 [:products :prod-1 :versions])))))
    (testing "discard-draft flips the latest draft to discarded"
      (let [s6 (-> s0
                   (step :create-product [:bank-0 :current 0])
                   (step :discard-draft [:prod-1]))]
        (is (= [(version :discarded 1)]
               (get-in s6 [:products :prod-1 :versions])))))
    (testing "open-draft after discard appends v2 in :draft"
      (let [s7 (-> s0
                   (step :create-product [:bank-0 :current 0])
                   (step :discard-draft [:prod-1])
                   (step :open-draft [:prod-1]))]
        (is (= [(version :discarded 1) (version :draft 2)]
               (get-in s7 [:products :prod-1 :versions])))))
    (testing "update-product-draft rewrites the latest version's window"
      (let [s8 (-> s0
                   (step :create-product [:bank-0 :current 0])
                   (step :update-product-draft
                         [:prod-1
                          {:effective-from 20454 :effective-to 21184}]))]
        (is (= [(assoc (version :draft 1)
                       :effective-from 20454
                       :effective-to 21184)]
               (get-in s8 [:products :prod-1 :versions])))))
    (testing "update-product-draft on a published version is a no-op"
      (let [s9 (-> s0
                   (step :create-product [:bank-0 :current 0])
                   (step :publish-product [:prod-1])
                   (step :update-product-draft
                         [:prod-1
                          {:effective-from 20454 :effective-to 21184}]))]
        (is (= [(version :published 1)]
               (get-in s9 [:products :prod-1 :versions])))))
    (testing "update-product-draft on an unknown product is a no-op"
      (let [s10 (step s0
                      :update-product-draft
                      [:prod-9 {:effective-from 20454 :effective-to nil}])]
        (is (= s0 s10))))))

(deftest inbound-transfer-test
  (let [s (-> init
              (step :create-bank []))]
    (testing "credits a fresh account"
      (let [s' (step s :inbound-transfer [:acct-0 500])]
        (is (= 500 (SUT/balance s' :acct-0)))))
    (testing "credit on a negative account that improves it is permitted"
      (let [breached (assoc-in s [:accounts :acct-0 :available] -50)
            s' (step breached :inbound-transfer [:acct-0 20])]
        (is (= -30 (SUT/balance s' :acct-0))
            "improving=true rule lets the move through")))
    (testing "credit on a negative account that overshoots zero is permitted"
      (let [breached (assoc-in s [:accounts :acct-0 :available] -50)
            s' (step breached :inbound-transfer [:acct-0 100])]
        (is (= 50 (SUT/balance s' :acct-0)))))))

(deftest outbound-payment-test
  (let [s (-> init
              (step :create-bank []))]
    (testing "a payment from a zero account is denied (would go negative)"
      (let [s' (step s :outbound-payment [:acct-0 100])]
        (is (= s s') "policy denies — state unchanged")))
    (testing "a payment that worsens an already-negative account is denied"
      (let [breached (assoc-in s [:accounts :acct-0 :available] -50)
            s' (step breached :outbound-payment [:acct-0 10])]
        (is (= breached s'))))
    (testing "a payment from a funded account completes at once"
      (let [funded (assoc-in s [:accounts :acct-0 :available] 200)
            s' (step funded :outbound-payment [:acct-0 80])]
        (is (= 120 (SUT/balance s' :acct-0)))
        (is (= {:debtor :acct-0 :amount 80 :status :completed}
               (get-in s' [:payments :pmt-0])))
        (is (= 3 (get-in s' [:accounts :acct-0 :transaction-legs]))
            "the reservation, then its clearing credit and posted debit")))
    (testing "a non-positive amount is a no-op"
      (let [funded (assoc-in s [:accounts :acct-0 :available] 200)]
        (is (= funded (step funded :outbound-payment [:acct-0 0])))
        (is (= funded (step funded :outbound-payment [:acct-0 -5])))))
    (testing "a payment to a known account credits it"
      (let [funded (-> s
                       (step :create-customer [:bank-0])
                       (assoc-in [:accounts :acct-0 :available] 200))
            s' (step funded :outbound-payment [:acct-0 :acct-1 50])]
        (is (= 150 (SUT/balance s' :acct-0)))
        (is (= 50 (SUT/balance s' :acct-1)))))
    (testing "a payment to a closed account still debits, its credit parked"
      (let [funded (-> s
                       (step :create-customer [:bank-0])
                       (step :close-account [:acct-1])
                       (assoc-in [:accounts :acct-0 :available] 200))
            s' (step funded :outbound-payment [:acct-0 :acct-1 50])]
        (is (= 150 (SUT/balance s' :acct-0)))
        (is (= 0 (SUT/balance s' :acct-1)))))))

(deftest internal-transfer-test
  ;; The same-org-only paths use a single org with two accounts, the
  ;; second added directly to the model state.
  (let [s (-> init
              (step :create-bank [])
              (assoc-in [:accounts :acct-1] {:bank :bank-0 :status :open}))]
    (testing "two-leg transfer between funded and zero account"
      (let [funded (assoc-in s [:accounts :acct-0 :available] 1000)
            s' (step funded :internal-transfer [:acct-0 :acct-1 400])]
        (is (= 600 (SUT/balance s' :acct-0)))
        (is (= 400 (SUT/balance s' :acct-1)))))
    (testing "transfer that would overdraw the source is denied — atomic"
      (let [s' (step s :internal-transfer [:acct-0 :acct-1 100])]
        (is (= 0 (SUT/balance s' :acct-0)))
        (is (= 0 (SUT/balance s' :acct-1))
            "credit leg also reverts when debit leg fails")))
    (testing "transfer that improves a breach on the source is permitted"
      (let [breached (-> s
                         (assoc-in [:accounts :acct-0 :available] -100)
                         (assoc-in [:accounts :acct-1 :available] 200))
            s' (step breached :internal-transfer [:acct-1 :acct-0 50])]
        (is (= -50 (SUT/balance s' :acct-0)) "improving — permitted")
        (is (= 150 (SUT/balance s' :acct-1))))))
  (testing "cross-org transfer is a no-op (model mirrors API rejection)"
    (let [s (-> init
                (step :create-bank [])
                (step :create-bank [])
                (assoc-in [:accounts :acct-0 :available] 1000))
          s' (step s :internal-transfer [:acct-0 :acct-1 400])]
      (is (= 1000 (SUT/balance s' :acct-0)) "debtor balance unchanged")
      (is (= 0 (SUT/balance s' :acct-1)) "creditor balance unchanged"))))

(deftest create-person-party-test
  (let [s0 (step init :create-bank [])]
    (testing "create-person-party records a person-party as :active"
      ;; The model treats the IDV chain as deterministic — the
      ;; default `\"Scenario\"` given-name routes to clear, so the
      ;; party is :active by the time the next verb runs.
      (let [s (step s0 :create-person-party [:bank-0])]
        (is (= :active (get-in s [:parties :party-1 :status])))
        (is (= :person (get-in s [:parties :party-1 :type])))
        (is (= :bank-0 (get-in s [:parties :party-1 :bank])))
        (is (= [:party-0 :party-1] (get-in s [:banks :bank-0 :parties])))))))

(deftest open-account-test
  (let [s (-> init
              (step :create-bank [])
              (step :create-person-party [:bank-0]))]
    (testing "opens on an active party and a published product"
      (let [s' (step s :open-account [:bank-0 :party-1 :prod-0])]
        (is (= {:available 0
                :credit-carry 0
                :interest-accrued 0
                :status :open
                :bank :bank-0
                :product :prod-0
                :party :party-1}
               (get-in s' [:accounts :acct-1])))
        (is (= [:acct-0 :acct-1] (get-in s' [:banks :bank-0 :accounts])))
        (is (= 2 (:next-id s')))))
    (testing "a draft product opens nothing, and the id is taken"
      (let [s' (-> s
                   (step :create-product [:bank-0 :current 0])
                   (step :open-account [:bank-0 :party-1 :prod-1]))]
        (is (nil? (get-in s' [:accounts :acct-1])))
        (is (= 2 (:next-id s')))))
    (testing "a party of another bank opens nothing"
      (let [s' (-> s
                   (step :create-bank [])
                   (step :open-account [:bank-0 :party-2 :prod-0]))]
        (is (nil? (get-in s' [:accounts :acct-2])))))))

(deftest fund-house-test
  (testing "the house account is not modelled, so nothing changes"
    (let [s (step init :create-bank [])]
      (is (= s (step s :fixture/fund-house [:bank-0 500000]))))))

(deftest weights-test
  (testing "create-bank is generated less often than any other command"
    (let [create-bank (get-in SUT/model [:create-bank :freq])]
      (doseq [[command {:keys [freq]}] (dissoc SUT/model :create-bank)]
        (is (< create-bank freq) (str command))))))

(deftest apply-fee-test
  (let [s (-> init
              (step :create-bank []))]
    (testing "fee posts on a positive account"
      (let [funded (assoc-in s [:accounts :acct-0 :available] 100)
            s' (step funded :fixture/apply-fee [:acct-0 30])]
        (is (= 70 (SUT/balance s' :acct-0)))))
    (testing "fee bypasses the available rule and can drive negative"
      (let [funded (assoc-in s [:accounts :acct-0 :available] 50)
            s' (step funded :fixture/apply-fee [:acct-0 200])]
        (is (= -150 (SUT/balance s' :acct-0))
            "fees ignore the available-balance rule by design")))))

(defn- count-limit
  [kind window value]
  {:kind {kind {}}
   :bound {:kind {:max {:aggregate {:kind {:count {:value value
                                                   :window window}}}}}}})

(defn- bound
  [state bank-id policy]
  (step state :bind-policy [bank-id (merge {:name "Test"} policy)]))

(deftest policies-test
  (let [s (-> init
              (step :create-bank [])
              (step :inbound-transfer [:acct-0 1000]))]
    (testing "a bank is bound to the tier at creation"
      (is (= [tier] (get-in s [:banks :bank-0 :policies]))))
    (testing "the available rule comes from the platform policy"
      (is (= {:min 0 :improving? true} (get-in s [:policies :available]))))
    (testing "a capability nothing allows is refused"
      (let [bare (-> SUT/init-state
                     (step :create-bank [])
                     (step :inbound-transfer [:acct-0 100]))]
        (is (= 0 (SUT/balance bare :acct-0)))))
    (testing "a bound deny refuses what the platform allows"
      (let [s' (-> s
                   (bound :bank-0
                          {:capabilities
                           [{:effect :effect-deny
                             :kind {:outbound-payment
                                    {:action :outbound-payment-action-send}}}]})
                   (step :outbound-payment [:acct-0 100]))]
        (is (= 1000 (SUT/balance s' :acct-0)))))
    (testing "a daily count lets payments through up to its value"
      (let [s' (-> s
                   (bound :bank-0
                          {:limits [(count-limit :outbound-payment
                                                 :time-window-daily
                                                 2)]})
                   (step :outbound-payment [:acct-0 100])
                   (step :outbound-payment [:acct-0 100])
                   (step :outbound-payment [:acct-0 100]))]
        (is (= 800 (SUT/balance s' :acct-0)))))
    (testing "an inbound over the daily count is received and not credited"
      (let [s' (-> s
                   (bound :bank-0
                          {:limits [(count-limit :inbound-payment
                                                 :time-window-daily
                                                 1)]})
                   (step :inbound-transfer [:acct-0 50]))]
        (is (= 1000 (SUT/balance s' :acct-0)))
        (is (= #{:in-0 :in-1} (:inbound-payments s')))))
    (testing "the account count counts the house account too"
      (let [s' (-> s
                   (step :create-person-party [:bank-0])
                   (bound :bank-0
                          {:limits [(count-limit :cash-account
                                                 :time-window-instant
                                                 3)]})
                   (step :open-account [:bank-0 :party-1 :prod-0])
                   (step :open-account [:bank-0 :party-1 :prod-0]))]
        (is (some? (get-in s' [:accounts :acct-1])))
        (is (nil? (get-in s' [:accounts :acct-2])))))
    (testing "closing a funded account takes a bound allow"
      (is (= :open
             (get-in (step s :close-account [:acct-0])
                     [:accounts :acct-0 :status])))
      (is (= :closed
             (get-in (-> s
                         (bound :bank-0
                                {:capabilities
                                 [{:effect :effect-allow
                                   :kind
                                   {:cash-account
                                    {:action
                                     :cash-account-action-close-non-zero}}}]})
                         (step :close-account [:acct-0]))
                     [:accounts :acct-0 :status]))))
    (testing "the platform allows one accrual run per date"
      (let [rated (-> s
                      (step :create-product [:bank-0 :savings 3650])
                      (step :publish-product [:prod-1])
                      (step :create-person-party [:bank-0])
                      (step :open-account [:bank-0 :party-1 :prod-1])
                      (assoc-in [:accounts :acct-1 :available] 100000))
            once (step rated :accrue-interest [:bank-0 20260501])
            twice (step once :accrue-interest [:bank-0 20260501])]
        (is (pos? (get-in once [:accounts :acct-1 :interest-accrued])))
        (is (= (get-in once [:accounts :acct-1 :interest-accrued])
               (get-in twice [:accounts :acct-1 :interest-accrued])))))))
