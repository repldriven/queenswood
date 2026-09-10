(ns com.repldriven.queenswood.test-model.interface-test
  (:require
    [com.repldriven.queenswood.test-model.interface :as SUT]

    [clojure.test :refer [deftest is testing]]))

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
    (let [s (step SUT/init-state :create-bank [])]
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
    (let [s (-> SUT/init-state
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
  (let [s0 (step SUT/init-state :create-bank [])]
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
  (let [s (-> SUT/init-state
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

(deftest outbound-transfer-test
  (let [s (-> SUT/init-state
              (step :create-bank []))]
    (testing "debit on a zero account is denied (would go negative)"
      (let [s' (step s :outbound-transfer [:acct-0 100])]
        (is (= 0 (SUT/balance s' :acct-0)) "policy denies — state unchanged")))
    (testing "debit that worsens an already-negative account is denied"
      (let [breached (assoc-in s [:accounts :acct-0 :available] -50)
            s' (step breached :outbound-transfer [:acct-0 10])]
        (is (= -50 (SUT/balance s' :acct-0)))))
    (testing "debit on a positive account stays in-bound"
      (let [funded (assoc-in s [:accounts :acct-0 :available] 200)
            s' (step funded :outbound-transfer [:acct-0 80])]
        (is (= 120 (SUT/balance s' :acct-0)))))))

(deftest internal-transfer-test
  ;; The same-org-only paths use a single org with two accounts.
  ;; `:open-account` isn't in the model registry, so we add the
  ;; second account directly to the model state.
  (let [s (-> SUT/init-state
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
    (let [s (-> SUT/init-state
                (step :create-bank [])
                (step :create-bank [])
                (assoc-in [:accounts :acct-0 :available] 1000))
          s' (step s :internal-transfer [:acct-0 :acct-1 400])]
      (is (= 1000 (SUT/balance s' :acct-0)) "debtor balance unchanged")
      (is (= 0 (SUT/balance s' :acct-1)) "creditor balance unchanged"))))

(deftest create-person-party-test
  (let [s0 (step SUT/init-state :create-bank [])]
    (testing "create-person-party records a person-party as :active"
      ;; The model treats the IDV chain as deterministic — the
      ;; default `\"Scenario\"` given-name routes to clear, so the
      ;; party is :active by the time the next verb runs.
      (let [s (step s0 :create-person-party [:bank-0])]
        (is (= :active (get-in s [:parties :party-1 :status])))
        (is (= :person (get-in s [:parties :party-1 :type])))
        (is (= :bank-0 (get-in s [:parties :party-1 :bank])))
        (is (= [:party-0 :party-1] (get-in s [:banks :bank-0 :parties])))))))

(deftest apply-fee-test
  (let [s (-> SUT/init-state
              (step :create-bank []))]
    (testing "fee posts on a positive account"
      (let [funded (assoc-in s [:accounts :acct-0 :available] 100)
            s' (step funded :apply-fee [:acct-0 30])]
        (is (= 70 (SUT/balance s' :acct-0)))))
    (testing "fee bypasses the available rule and can drive negative"
      (let [funded (assoc-in s [:accounts :acct-0 :available] 50)
            s' (step funded :apply-fee [:acct-0 200])]
        (is (= -150 (SUT/balance s' :acct-0))
            "fees ignore the available-balance rule by design")))))
