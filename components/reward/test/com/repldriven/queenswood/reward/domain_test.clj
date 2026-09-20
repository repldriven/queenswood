(ns com.repldriven.queenswood.reward.domain-test
  "Pure-function tests for what the reward pass decides without a
  store: which accounts are eligible, what a version promises, the
  legs a reward posts, and the row a paid or deferred reward leaves."
  (:require
    [com.repldriven.queenswood.reward.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private account
  {:bank-id "bnk.1"
   :account-id "acc.1"
   :party-id "pty.1"
   :product-id "prd.1"
   :version-id "prv.1"
   :product-type :product-type-sub-ledger-current
   :currency "GBP"
   :account-status :cash-account-status-opened})

(def ^:private house
  {:bank-id "bnk.1"
   :account-id "acc.house"
   :product-type :product-type-sub-ledger-own-funds
   :currency "GBP"
   :account-status :cash-account-status-opened})

(deftest eligible-test
  (testing "an opened customer account is eligible"
    (is (SUT/eligible? account)))
  (testing "an account still opening, suspended or closed is not"
    (doseq [status [:cash-account-status-opening
                    :cash-account-status-suspended
                    :cash-account-status-closed]]
      (is (not (SUT/eligible? (assoc account :account-status status))))))
  (testing "the bank's own account is not" (is (not (SUT/eligible? house)))))

(deftest promised-test
  (testing "a version's reward is its amount"
    (is (= 1000 (SUT/promised {:opening-reward {:amount 1000}}))))
  (testing "a version with no term promises nothing"
    (is (nil? (SUT/promised {:interest-rate-bps 250})))))

(deftest reward-transaction-test
  (let [reward (SUT/new-reward account 1000 "run.1")
        transaction (SUT/reward-transaction house account reward)
        [debit credit] (:legs transaction)]
    (testing "the row is due, keyed by the account, in its currency"
      (is (= :reward-status-due (:status reward)))
      (is (= :reward-kind-opening (:kind reward)))
      (is (= "GBP" (:currency reward)))
      (is (re-matches #"rwd\..+" (:reward-id reward))))
    (testing "the posting is a reward, keyed by the account, from the house"
      (is (= :transaction-type-reward (:transaction-type transaction)))
      (is (= "reward-acc.1" (:idempotency-key transaction)))
      (is (= "Welcome reward" (:reference transaction)))
      (is (= "acc.house" (:account-id debit)))
      (is (= :leg-side-debit (:side debit)))
      (is (= :product-type-sub-ledger-own-funds (:product-type debit)))
      (is (= "acc.1" (:account-id credit)))
      (is (= :leg-side-credit (:side credit)))
      (is (= :product-type-sub-ledger-current (:product-type credit)))
      (is (= [1000 1000] (map :amount [debit credit]))))))

(deftest paid-and-deferred-test
  (let [reward (SUT/new-reward account 1000 "run.1")
        refusal (error/reject :policy/limit-exceeded
                              {:message "Available balance would go negative"})
        deferred (SUT/deferred reward refusal "run.2")
        paid (SUT/paid deferred "txn.1" "run.3")]
    (testing "a deferred row stays due and says why"
      (is (= :reward-status-due (:status deferred)))
      (is (= "run.2" (:run-id deferred)))
      (is (re-find #"limit-exceeded" (:error deferred))))
    (testing "a paid row carries the transaction and drops the error"
      (is (SUT/paid? paid))
      (is (= "txn.1" (:transaction-id paid)))
      (is (= "run.3" (:run-id paid)))
      (is (some? (:paid-at paid)))
      (is (not (contains? paid :error))))))
