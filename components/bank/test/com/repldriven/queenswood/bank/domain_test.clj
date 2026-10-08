(ns com.repldriven.queenswood.bank.domain-test
  "Pure-function tests for bank provisioning: `:bank/unknown-tier` when
  the tier resolves to no policies, `:bank/company-not-active`
  when the bound company snapshot is not active, the actor a create
  records when its command carries none, `:idv/unsupported-criteria`
  when a tier requires what the identity provider does not establish,
  and the providers a create chooses."
  (:require
    [com.repldriven.queenswood.bank.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private permissive-policies
  "Single allow-everything policy — an empty fields map in the oneof
  variant matches every request because the matcher only constrains
  on set fields."
  [{:enabled true
    :capabilities [{:kind {:bank {}} :effect :effect-allow}
                   {:kind {:idv {}} :effect :effect-allow}]}])

(def ^:private idv-provider
  "A provider declaration that establishes nothing, which meets
  `permissive-policies` since they require nothing."
  {:verifies [] :screens []})

(def ^:private address-tier-policies
  "A tier requiring a person's address to be verified."
  [{:policy-id "pol.address"
    :enabled true
    :capabilities [{:kind {:idv {:action :idv-action-accept
                                 :filters [{:party-type :party-type-person
                                            :unverified
                                            :idv-verification-address}]}}
                    :effect :effect-deny}]}])

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
                             "micro"
                             active-binding
                             tier-policies
                             permissive-policies
                             idv-provider)]
      (is (re-find #"^bnk\." (:bank-id bank)))
      (is (= :bank-status-test (:status bank)))
      (is (= "micro" (:tier bank)))
      (is (= active-binding (:company-binding bank)))))
  (testing "omits :company-binding for admin-provisioned banks"
    (let [bank (SUT/new-bank "Acme"
                             :bank-status-test
                             "micro"
                             nil
                             tier-policies
                             permissive-policies
                             idv-provider)]
      (is (not (contains? bank :company-binding)))))
  (testing "rejects a nil tier"
    (let [r (SUT/new-bank "Acme"
                          :bank-status-test
                          nil
                          nil
                          []
                          permissive-policies
                          idv-provider)]
      (is (error/rejection? r))
      (is (= :bank/unknown-tier (error/kind r)))))
  (testing "rejects a tier that resolves to no policies"
    (let [r (SUT/new-bank "Acme"
                          :bank-status-test
                          "no-such-tier"
                          nil
                          []
                          permissive-policies
                          idv-provider)]
      (is (error/rejection? r))
      (is (= :bank/unknown-tier (error/kind r)))))
  (testing "rejects a binding whose company is not active"
    (let [r (SUT/new-bank "Acme"
                          :bank-status-test
                          "micro"
                          (assoc active-binding :company-status "dissolved")
                          tier-policies
                          permissive-policies
                          idv-provider)]
      (is (error/rejection? r))
      (is (= :bank/company-not-active (error/kind r)))))
  (testing "rejects a tier requiring what the identity provider lacks"
    (let [r (SUT/new-bank "Acme"
                          :bank-status-test
                          "address"
                          nil
                          address-tier-policies
                          permissive-policies
                          idv-provider)]
      (is (error/rejection? r))
      (is (= :idv/unsupported-criteria (error/kind r)))
      (is (= [:idv-verification-address] (:unmet (error/payload r)))))))

(def ^:private test-bank {:bank-id "bnk.1" :status :bank-status-live})

(def ^:private new-tier-policies [{:policy-id "pol.new"}])

(deftest change-tier-test
  (testing "rebinds and stamps the new tier"
    (let [bank (SUT/change-tier test-bank
                                "growth"
                                new-tier-policies
                                permissive-policies
                                idv-provider)]
      (is (= "growth" (:tier bank)))
      (is (= "bnk.1" (:bank-id bank)))))
  (testing "rejects a bank that isn't test or live"
    (let [r (SUT/change-tier (assoc test-bank :status :bank-status-unknown)
                             "growth"
                             new-tier-policies
                             permissive-policies
                             idv-provider)]
      (is (error/rejection? r))
      (is (= :bank/invalid-status (error/kind r)))))
  (testing "rejects a tier with no matching policies"
    (let [r (SUT/change-tier test-bank
                             "unknown-tier"
                             []
                             permissive-policies
                             idv-provider)]
      (is (error/rejection? r))
      (is (= :bank/unknown-tier (error/kind r)))))
  (testing "rejects a tier requiring what the identity provider lacks"
    (let [r (SUT/change-tier test-bank
                             "address"
                             address-tier-policies
                             permissive-policies
                             idv-provider)]
      (is (error/rejection? r))
      (is (= :idv/unsupported-criteria (error/kind r)))
      (is (= [:idv-verification-address] (:unmet (error/payload r)))))))

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

(def ^:private offered
  "Two kinds offered, as their providers instances carry them."
  [{:kind "payment" :default :rails :providers {:rails {} :pooled {}}}
   {:kind "idv" :default :verifier :providers {:verifier {}}}])

(deftest choose-providers-test
  (testing "a kind named takes the provider named"
    (is (= [{:kind "idv" :provider "verifier"}
            {:kind "payment" :provider "pooled"}]
           (SUT/choose-providers offered
                                 [{:kind "payment" :provider "pooled"}]))))
  (testing "every kind offered is recorded, the default for one not named"
    (is (= [{:kind "idv" :provider "verifier"}
            {:kind "payment" :provider "rails"}]
           (SUT/choose-providers offered []))))
  (testing "a provider not offered is refused, naming what is offered"
    (let [r (SUT/choose-providers offered
                                  [{:kind "payment" :provider "other"}])]
      (is (= :bank/unknown-provider (error/kind r)))
      (is (= {"payment" ["rails" "pooled"] "idv" ["verifier"]}
             (:offered (error/payload r))))))
  (testing "a kind not offered is refused"
    (is (= :bank/unknown-provider
           (error/kind (SUT/choose-providers offered
                                             [{:kind "banking"
                                               :provider "rails"}]))))))
