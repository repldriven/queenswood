(ns com.repldriven.queenswood.bank.domain-test
  "Pure-function tests for bank provisioning: `:bank/unknown-tier` when
  the tier resolves to no policies, `:onboarding/company-not-active`
  when the bound company snapshot is not active, the actor a create
  records when its command carries none, and `:bank/already-exists`
  when a key has already created a bank."
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
  "A tier that resolves to at least one policy — creation rejects an
  empty list."
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
                             active-binding
                             tier-policies
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
                             nil
                             tier-policies
                             permissive-policies)]
      (is (not (contains? bank :company-binding)))))
  (testing "rejects a nil tier"
    (let [r (SUT/new-bank "Acme"
                          :bank-status-test
                          "000001"
                          nil
                          nil
                          []
                          permissive-policies)]
      (is (error/rejection? r))
      (is (= :bank/unknown-tier (error/kind r)))))
  (testing "rejects a tier that resolves to no policies"
    (let [r (SUT/new-bank "Acme"
                          :bank-status-test
                          "000001"
                          "no-such-tier"
                          nil
                          []
                          permissive-policies)]
      (is (error/rejection? r))
      (is (= :bank/unknown-tier (error/kind r)))))
  (testing "rejects a binding whose company is not active"
    (let [r (SUT/new-bank "Acme"
                          :bank-status-test
                          "000001"
                          "micro"
                          (assoc active-binding :company-status "dissolved")
                          tier-policies
                          permissive-policies)]
      (is (error/rejection? r))
      (is (= :onboarding/company-not-active (error/kind r))))))

(deftest creation-actor-test
  (let [operator {:kind :actor-kind-operator :principal-id "queenswood-admin"}]
    (testing "the command's actor when it carries one"
      (is (= operator (SUT/creation-actor operator {:user-id "usr.1"}))))
    (testing "the membership's user as a member when the command has none"
      (is (= {:kind :actor-kind-member :principal-id "usr.1"}
             (SUT/creation-actor nil {:user-id "usr.1" :role :role-owner}))))
    (testing "an unknown operator when there is neither"
      (is (= {:kind :actor-kind-operator :principal-id "unknown"}
             (SUT/creation-actor nil nil))))))

(deftest check-first-creation-test
  (testing "nil for the first creation under a key"
    (is (nil? (SUT/check-first-creation "ik-bank-00000001" 1))))
  (testing "nil when the command carries no key"
    (is (nil? (SUT/check-first-creation nil nil))))
  (testing "rejects a second creation under a key"
    (let [r (SUT/check-first-creation "ik-bank-00000001" 2)]
      (is (error/rejection? r))
      (is (= :bank/already-exists (error/kind r)))
      (is (= "ik-bank-00000001" (:idempotency-key (error/payload r)))))))

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
