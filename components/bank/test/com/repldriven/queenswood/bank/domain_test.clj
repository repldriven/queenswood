(ns com.repldriven.queenswood.bank.domain-test
  "Pure-function tests for the rejection paths of bank provisioning:
  `:bank/unknown-tier` when the requested tier matches no policy,
  `:onboarding/company-not-active` when the bound company snapshot is
  not active, and `:membership/already-exists` from the sole-membership
  check core runs inside the provisioning transaction."
  (:require
    [com.repldriven.queenswood.bank.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private permissive-policies
  "Single allow-everything policy — an empty fields map in the oneof
  variant matches every request because the matcher only constrains
  on set fields."
  [{:enabled true :capabilities [{:kind {:bank {}} :effect :effect-allow}]}])

(def ^:private tier-policies
  "One policy is enough — the guard asks whether the tier resolves to
  anything, not what it resolves to."
  [{:policy-id "pol.micro"}])

(def ^:private active-binding
  {:registry "uk-companies-house"
   :company-number "12345678"
   :company-name "Acme Ltd"
   :company-status "active"})

(deftest new-bank-test
  (testing "builds a bnk.-prefixed bank stamped with the binding"
    (let [bank (SUT/new-bank "Acme"
                             :bank-status-test
                             "000001"
                             "micro"
                             tier-policies
                             active-binding
                             permissive-policies)]
      (is (re-find #"^bnk\." (:bank-id bank)))
      (is (= :bank-status-test (:status bank)))
      (is (= "000001" (:sort-code bank)))
      (is (= "micro" (:tier bank)))
      (is (= active-binding (:company-binding bank)))))
  (testing "omits :company-binding for admin-provisioned banks"
    (let [bank (SUT/new-bank "Acme"
                             :bank-status-test
                             "000001"
                             "micro"
                             tier-policies
                             nil
                             permissive-policies)]
      (is (not (contains? bank :company-binding)))))
  (testing "omits :tier when none is supplied"
    (let [bank (SUT/new-bank "Acme"
                             :bank-status-test
                             "000001"
                             nil
                             []
                             nil
                             permissive-policies)]
      (is (not (contains? bank :tier)))))
  (testing "rejects a named tier that matches no policy"
    (let [r (SUT/new-bank "Acme"
                          :bank-status-test
                          "000001"
                          "no-such-tier"
                          []
                          nil
                          permissive-policies)]
      (is (error/rejection? r))
      (is (= :bank/unknown-tier (error/kind r)))))
  (testing "rejects a binding whose company is not active"
    (let [r (SUT/new-bank "Acme"
                          :bank-status-test
                          "000001"
                          "micro"
                          tier-policies
                          (assoc active-binding :company-status "dissolved")
                          permissive-policies)]
      (is (error/rejection? r))
      (is (= :onboarding/company-not-active (error/kind r))))))

(deftest check-sole-membership-test
  (testing "nil when the user has no memberships"
    (is (nil? (SUT/check-sole-membership "usr.1" []))))
  (testing "rejects when the user already belongs to a bank"
    (let [r (SUT/check-sole-membership "usr.1" [{:bank-id "bnk.1"}])]
      (is (error/rejection? r))
      (is (= :membership/already-exists (error/kind r))))))

(def ^:private test-bank {:bank-id "bnk.1" :status :bank-status-live})

(def ^:private new-tier-policies [{:policy-id "pol.new"}])

(deftest change-tier-test
  (testing "rebinds and stamps the new tier"
    (let [bank (SUT/change-tier test-bank "growth" new-tier-policies)]
      (is (= "growth" (:tier bank)))
      (is (= "bnk.1" (:bank-id bank)))))
  (testing "rejects a bank that isn't test or live"
    (let [r (SUT/change-tier (assoc test-bank :status :bank-status-unknown)
                             "growth"
                             new-tier-policies)]
      (is (error/rejection? r))
      (is (= :bank/invalid-status (error/kind r)))))
  (testing "rejects a tier with no matching policies"
    (let [r (SUT/change-tier test-bank "unknown-tier" [])]
      (is (error/rejection? r))
      (is (= :bank/unknown-tier (error/kind r))))))

(deftest change-status-test
  (testing "flips status"
    (let [bank (SUT/change-status test-bank :bank-status-test)]
      (is (= :bank-status-test (:status bank)))
      (is (= "bnk.1" (:bank-id bank)))))
  (testing "rejects a bank that isn't test or live"
    (let [r (SUT/change-status (assoc test-bank :status :bank-status-unknown)
                               :bank-status-test)]
      (is (error/rejection? r))
      (is (= :bank/invalid-status (error/kind r)))))
  (testing "rejects flipping to the same status"
    (let [r (SUT/change-status test-bank :bank-status-live)]
      (is (error/rejection? r))
      (is (= :bank/invalid-status (error/kind r))))))
