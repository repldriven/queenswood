(ns com.repldriven.queenswood.email.domain-test
  (:require
    [com.repldriven.queenswood.email.domain :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private now 1700000000000)

(def ^:private event
  {:bank-id "bnk.test" :invitation-id "inv.test" :expires-at 1700604800000})

(def ^:private delivery
  (assoc (SUT/new-invitation-delivery event "evt.test" now)
         :status :email-delivery-status-in-flight
         :next-attempt-at (+ now 60000)))

(def ^:private invitation
  {:bank-id "bnk.test"
   :invitation-id "inv.test"
   :status :invitation-status-pending})

(deftest new-invitation-delivery-test
  (testing
    "a delivery is pending and due now, about the invitation and keyed by
    the event it was written for"
    (let [written (SUT/new-invitation-delivery event "evt.test" now)]
      (is (= {:bank-id "bnk.test"
              :kind :email-kind-invitation
              :status :email-delivery-status-pending
              :kind-id "inv.test"
              :idempotency-key "evt.test"
              :created-at now
              :attempt-count 0
              :next-attempt-at now}
             (dissoc written :delivery-id)))
      (is (re-matches #"eml\..+" (:delivery-id written))))))

(def ^:private retry-policy
  {:initial-backoff-ms 30000
   :backoff-growth 4
   :max-backoff-ms 14400000
   :max-attempts 11
   :max-age-ms 86400000})

(deftest record-failure-test
  (testing "a failed attempt is retried after the policy's backoff"
    (let [failed
          (SUT/record-failure delivery retry-policy "connection refused" now)]
      (is (= :email-delivery-status-pending (:status failed)))
      (is (= 1 (:attempt-count failed)))
      (is (= (+ now 30000) (:next-attempt-at failed)))
      (is (not (contains? failed :failure-reason)))))
  (testing "the backoff grows by the policy's growth"
    (let [failed (SUT/record-failure (assoc delivery :attempt-count 1)
                                     retry-policy
                                     "connection refused"
                                     now)]
      (is (= (+ now 120000) (:next-attempt-at failed)))))
  (testing "the last attempt fails the delivery with its reason"
    (let [last-try (assoc delivery :attempt-count 10)
          failed
          (SUT/record-failure last-try retry-policy "connection refused" now)]
      (is (= :email-delivery-status-failed (:status failed)))
      (is (= 11 (:attempt-count failed)))
      (is (= "connection refused" (:failure-reason failed)))
      (is (not (contains? failed :next-attempt-at)))))
  (testing "a delivery older than the maximum age fails on its next failure"
    (let [failed (SUT/record-failure delivery
                                     retry-policy
                                     "connection refused"
                                     (+ now 86400001))]
      (is (= :email-delivery-status-failed (:status failed)))
      (is (= 1 (:attempt-count failed))))))

(deftest supersession-test
  (testing "a pending invitation with no newer email wants this one"
    (is (nil? (SUT/supersession invitation false))))
  (doseq [status [:invitation-status-withdrawn :invitation-status-accepted
                  :invitation-status-declined :invitation-status-expired]]
    (testing (str "an invitation that is " (name status) " wants none")
      (is (some? (SUT/supersession (assoc invitation :status status) false)))))
  (testing "a newer email about the same invitation supersedes this one"
    (is (= "a newer email about the same invitation"
           (SUT/supersession invitation true)))))

(deftest outcomes-release-the-claim-test
  (testing
    "a sent delivery carries its Message-ID, when it was sent, and
    no next attempt"
    (let [sent (SUT/mark-sent delivery "<id@queenswood.local>" now)]
      (is (= :email-delivery-status-sent (:status sent)))
      (is (= "<id@queenswood.local>" (:message-id sent)))
      (is (= now (:sent-at sent)))
      (is (= 1 (:attempt-count sent)))
      (is (not (contains? sent :next-attempt-at)))))
  (testing "a superseded delivery has no next attempt"
    (let [superseded (SUT/mark-superseded delivery now)]
      (is (= :email-delivery-status-superseded (:status superseded)))
      (is (not (contains? superseded :next-attempt-at))))))
