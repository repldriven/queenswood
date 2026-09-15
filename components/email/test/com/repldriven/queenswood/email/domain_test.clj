(ns com.repldriven.queenswood.email.domain-test
  (:require
    [com.repldriven.queenswood.email.domain :as SUT]

    [clojure.test :refer [deftest is testing]]))

(def ^:private now 1700000000000)

(def ^:private expires-at 1700604800000)

(def ^:private event
  {:bank-id "bnk.test" :invitation-id "inv.test" :expires-at expires-at})

(def ^:private delivery
  (assoc (SUT/new-invitation-delivery event "evt.test" now)
         :status :email-delivery-status-in-flight
         :claimed-by "runner"
         :claim-lease-expires-at (+ now SUT/claim-lease-ms)))

(def ^:private invitation
  {:bank-id "bnk.test"
   :invitation-id "inv.test"
   :status :invitation-status-pending
   :expires-at expires-at})

(deftest new-invitation-delivery-test
  (testing
    "a delivery is pending and due now, for the event it was
            written for"
    (let [written (SUT/new-invitation-delivery event "evt.test" now)]
      (is (= {:bank-id "bnk.test"
              :kind :email-kind-invitation
              :invitation-id "inv.test"
              :expires-at expires-at
              :changelog-event-id "evt.test"
              :status :email-delivery-status-pending
              :next-attempt-at now
              :created-at now
              :updated-at now}
             (dissoc written :delivery-id)))
      (is (re-matches #"eml\..+" (:delivery-id written))))))

(deftest retry-schedule-test
  (testing "the delays grow geometrically and stop growing at the cap"
    (is (= SUT/retry-base-ms (first SUT/retry-schedule-ms)))
    (is (= (* SUT/retry-growth SUT/retry-base-ms)
           (second SUT/retry-schedule-ms)))
    (is (every? (fn [delay] (<= delay SUT/retry-max-interval-ms))
                SUT/retry-schedule-ms))
    (is (<= (reduce + SUT/retry-schedule-ms) SUT/retry-span-ms)))
  (testing "a failed attempt is retried after the scheduled delay"
    (let [failed (SUT/record-failure delivery "connection refused" now)]
      (is (= :email-delivery-status-pending (:status failed)))
      (is (= 1 (:attempts failed)))
      (is (= (+ now SUT/retry-base-ms) (:next-attempt-at failed)))
      (is (= "connection refused" (:last-error failed)))
      (is (not-any? (partial contains? failed)
                    [:claimed-by :claim-lease-expires-at]))))
  (testing "the last attempt fails the delivery and keeps it"
    (let [last-try (assoc delivery :attempts (dec SUT/max-attempts))
          failed (SUT/record-failure last-try "connection refused" now)]
      (is (= :email-delivery-status-failed (:status failed)))
      (is (= SUT/max-attempts (:attempts failed)))
      (is (not (contains? failed :next-attempt-at))))))

(deftest supersession-test
  (testing "a pending invitation for the same expiry wants the email"
    (is (nil? (SUT/supersession delivery invitation))))
  (doseq [status [:invitation-status-withdrawn :invitation-status-accepted
                  :invitation-status-declined :invitation-status-expired]]
    (testing (str "an invitation that is " (name status) " wants none")
      (is (some? (SUT/supersession delivery
                                   (assoc invitation :status status))))))
  (testing "an invitation sent again since wants none"
    (is (= "invitation was sent again"
           (SUT/supersession
            delivery
            (assoc invitation :expires-at (+ expires-at 1000)))))))

(deftest outcomes-release-the-claim-test
  (testing "a sent delivery carries its Message-ID and no claim"
    (let [sent (SUT/mark-sent delivery "<id@queenswood.local>" now)]
      (is (= :email-delivery-status-sent (:status sent)))
      (is (= "<id@queenswood.local>" (:message-id sent)))
      (is (= 1 (:attempts sent)))
      (is (not-any? (partial contains? sent)
                    [:claimed-by :claim-lease-expires-at :next-attempt-at]))))
  (testing "a superseded delivery carries its reason and no claim"
    (let [superseded
          (SUT/mark-superseded delivery "invitation is withdrawn" now)]
      (is (= :email-delivery-status-superseded (:status superseded)))
      (is (= "invitation is withdrawn" (:last-error superseded)))
      (is (not-any? (partial contains? superseded)
                    [:claimed-by :claim-lease-expires-at :next-attempt-at])))))
