(ns com.repldriven.queenswood.party.domain-test
  "Pure-function tests for the suspend/resume/close/merge source-state
  guards. No FDB, no processor — this pins the lifecycle-transition
  convention (docs/recipes/code/lifecycle-transitions.md): reject
  before any capability check when a party isn't in a valid source
  state."
  (:require
    [com.repldriven.queenswood.party.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private operator
  {:kind :actor-kind-operator :principal-id "queenswood-admin"})

(defn- party
  [party-id status]
  {:bank-id "bnk.test"
   :party-id party-id
   :display-name "Test Party"
   :status status
   :party-type :party-type-person
   :created-at 0
   :created-by operator
   :updated-at 0})

(defn- policy-allowing
  [& actions]
  {:enabled true
   :capabilities (mapv (fn [action]
                         {:effect :effect-allow
                          :kind {:party {:action action}}})
                       actions)})

(def ^:private allow-suspend [(policy-allowing :party-action-suspend)])
(def ^:private allow-resume [(policy-allowing :party-action-resume)])
(def ^:private allow-close [(policy-allowing :party-action-close)])

(deftest suspend-party-source-state-guard-test
  (testing
    "suspending a party not in :party-status-active is rejected,
           regardless of policy"
    (doseq [status [:party-status-pending
                    :party-status-suspended
                    :party-status-closed
                    :party-status-rejected
                    :party-status-merged]]
      (let [result (SUT/suspend-party (party "pty.subject" status)
                                      operator
                                      allow-suspend)]
        (is (error/rejection? result))
        (is (= :party/invalid-status (error/kind result)))
        (is (= status (:status (error/payload result))))))))

(deftest suspend-party-capability-denied-test
  (testing "no allow capability for party-action-suspend denies the suspend"
    (let [result (SUT/suspend-party (party "pty.subject" :party-status-active)
                                    operator
                                    [])]
      (is (error/unauthorized? result)))))

(deftest suspend-party-happy-test
  (testing "an active party flips to suspended"
    (let [result (SUT/suspend-party (party "pty.subject" :party-status-active)
                                    operator
                                    allow-suspend)]
      (is (= :party-status-suspended (:status result)))
      (is (= operator (:suspended-by result)))
      (is (= (:suspended-at result) (:updated-at result))))))

(deftest resume-party-source-state-guard-test
  (testing
    "resuming a party not in :party-status-suspended is rejected,
           regardless of policy — close is terminal, not resumable"
    (doseq [status [:party-status-pending
                    :party-status-active
                    :party-status-closed
                    :party-status-rejected
                    :party-status-merged]]
      (let [result (SUT/resume-party (party "pty.subject" status)
                                     operator
                                     allow-resume)]
        (is (error/rejection? result))
        (is (= :party/invalid-status (error/kind result)))
        (is (= status (:status (error/payload result))))))))

(deftest resume-party-capability-denied-test
  (testing "no allow capability for party-action-resume denies the resume"
    (let [result (SUT/resume-party (party "pty.subject" :party-status-suspended)
                                   operator
                                   [])]
      (is (error/unauthorized? result)))))

(deftest resume-party-happy-test
  (testing "a suspended party flips back to active"
    (let [result (SUT/resume-party (party "pty.subject" :party-status-suspended)
                                   operator
                                   allow-resume)]
      (is (= :party-status-active (:status result)))
      (is (int? (:updated-at result))))))

(deftest close-party-source-state-guard-test
  (testing
    "closing a party not in :party-status-active or
           :party-status-suspended is rejected, regardless of policy"
    (doseq [status [:party-status-pending
                    :party-status-closed
                    :party-status-rejected
                    :party-status-merged]]
      (let [result (SUT/close-party (party "pty.subject" status)
                                    operator
                                    false
                                    allow-close)]
        (is (error/rejection? result))
        (is (= :party/invalid-status (error/kind result)))
        (is (= status (:status (error/payload result))))))))

(deftest close-party-capability-denied-test
  (testing "no allow capability for party-action-close denies the close"
    (let [result (SUT/close-party (party "pty.subject" :party-status-active)
                                  operator
                                  false
                                  [])]
      (is (error/unauthorized? result)))))

(deftest close-party-open-accounts-rejected-test
  (testing "a party with any non-closed cash account is rejected"
    (let [result (SUT/close-party (party "pty.subject" :party-status-active)
                                  operator
                                  true
                                  allow-close)]
      (is (error/rejection? result))
      (is (= :party/open-accounts (error/kind result)))
      (is (= "pty.subject" (:party-id (error/payload result)))))))

(deftest close-party-happy-test
  (testing "an active or suspended party with no open accounts closes"
    (doseq [status [:party-status-active :party-status-suspended]]
      (let [result (SUT/close-party (party "pty.subject" status)
                                    operator
                                    false
                                    allow-close)]
        (is (= :party-status-closed (:status result)))
        (is (int? (:updated-at result)))))))

(def ^:private survivor (party "pty.survivor" :party-status-active))
(def ^:private merged-away (party "pty.merged" :party-status-suspended))
(def ^:private allow-merge [(policy-allowing :party-action-merge)])

(deftest merge-party-into-self-rejected-test
  (testing "merging a party into itself is rejected before any status guard"
    (let [same (party "pty.self" :party-status-suspended)
          result (SUT/merge-party same same operator false allow-merge)]
      (is (error/rejection? result))
      (is (= :party/merge-into-self (error/kind result))))))

(deftest merge-party-source-state-guard-test
  (testing
    "merging a party not in :party-status-suspended is rejected,
           regardless of policy"
    (doseq [status [:party-status-pending
                    :party-status-active
                    :party-status-closed
                    :party-status-rejected
                    :party-status-merged]]
      (let [away (party "pty.merged" status)
            result (SUT/merge-party survivor away operator false allow-merge)]
        (is (error/rejection? result))
        (is (= :party/invalid-status (error/kind result)))
        (is (= "pty.merged" (:party-id (error/payload result))))
        (is (= status (:status (error/payload result))))))))

(deftest merge-party-survivor-must-be-active-test
  (testing "a non-active survivor is rejected, regardless of policy"
    (doseq [status [:party-status-pending
                    :party-status-suspended
                    :party-status-closed
                    :party-status-rejected
                    :party-status-merged]]
      (let [not-active-survivor (party "pty.survivor" status)
            result (SUT/merge-party not-active-survivor
                                    merged-away
                                    operator
                                    false
                                    allow-merge)]
        (is (error/rejection? result))
        (is (= :party/invalid-status (error/kind result)))
        (is (= "pty.survivor" (:party-id (error/payload result))))
        (is (= status (:status (error/payload result))))))))

(deftest merge-party-capability-denied-test
  (testing "no allow capability for party-action-merge denies the merge"
    (let [result (SUT/merge-party survivor merged-away operator false [])]
      (is (error/unauthorized? result)))))

(deftest merge-party-open-accounts-rejected-test
  (testing "a merged-away party with any non-closed cash account is rejected"
    (let [result
          (SUT/merge-party survivor merged-away operator true allow-merge)]
      (is (error/rejection? result))
      (is (= :party/open-accounts (error/kind result)))
      (is (= "pty.merged" (:party-id (error/payload result)))))))

(deftest merge-party-happy-test
  (testing
    "a suspended party merged into an active survivor flips to
           merged and records the survivor's id as the pointer"
    (let [result
          (SUT/merge-party survivor merged-away operator false allow-merge)]
      (is (= :party-status-merged (:status result)))
      (is (= "pty.survivor" (:merged-into-party-id result)))
      (is (= "pty.merged" (:party-id result)))
      (is (= operator (:merged-by result)))
      (is (= (:merged-at result) (:updated-at result))))))
