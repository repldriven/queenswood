(ns com.repldriven.queenswood.bank.changelog-test
  (:require
    [com.repldriven.queenswood.bank.changelog :as changelog]

    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(deftest changelog-carries-the-shared-envelope-test
  (testing
    "a status transition serialises as a ChangelogEvent the
           generic relay can decode without knowing this domain"
    (let [bytes (changelog/status-changed {:bank-id "bnk.changelog.1"
                                           :status-before :bank-status-test
                                           :status-after :bank-status-live})
          decoded (schema/pb->ChangelogEvent bytes)]
      (is (= "bank-status-changed" (:event-name decoded)))
      (is (= "bnk.changelog.1:status:bank-status-live" (:dedup-key decoded)))
      (is (= "bnk.changelog.1" (:causation-id decoded)))
      (is (seq (:event-id decoded)) "an event-id is minted for dedup")
      (is (pos? (count (:payload decoded))) "the Avro payload is carried"))))

(deftest status-is-an-enum-not-a-string-test
  (testing
    "a stringified status is rejected rather than carried — the
           payload field is the same Avro enum the Bank record uses, so
           a status that isn't one of the symbols cannot be published"
    (let [result (changelog/status-changed {:bank-id "bnk.changelog.2"
                                            :status-after "bank-status-live"})]
      (is (error/anomaly? result)))))

(deftest bank-creation-has-no-status-before-test
  (testing "a newly created bank has no source status"
    (let [bytes (changelog/status-changed {:bank-id "bnk.changelog.3"
                                           :status-after :bank-status-test})
          decoded (schema/pb->ChangelogEvent bytes)]
      (is (= "bnk.changelog.3:status:bank-status-test" (:dedup-key decoded))))))

(deftest tier-change-is-its-own-event-test
  (testing
    "a tier transition carries its own event name and payload rather
           than a status change from a status to itself"
    (let [bytes (changelog/tier-changed {:bank-id "bnk.changelog.4"
                                         :tier-before "micro"
                                         :tier-after "growth"})
          decoded (schema/pb->ChangelogEvent bytes)]
      (is (= "bank-tier-changed" (:event-name decoded)))
      (is (= "bnk.changelog.4:tier:growth" (:dedup-key decoded)))
      (is (= "bnk.changelog.4" (:causation-id decoded)))
      (is (pos? (count (:payload decoded)))))))

(deftest a-tier-cannot-collide-with-a-status-test
  (testing
    "a tier named after a status keys differently from the
           transition into that status, so the two can never share a
           dedup key"
    (let [tier (schema/pb->ChangelogEvent (changelog/tier-changed
                                           {:bank-id "bnk.changelog.5"
                                            :tier-after "bank-status-live"}))
          status (schema/pb->ChangelogEvent
                  (changelog/status-changed {:bank-id "bnk.changelog.5"
                                             :status-after :bank-status-live}))]
      (is (not= (:dedup-key tier) (:dedup-key status))))))

(deftest a-tierless-bank-has-no-tier-before-test
  (testing "a bank given its first tier has no source tier"
    (let [bytes (changelog/tier-changed {:bank-id "bnk.changelog.6"
                                         :tier-after "micro"})
          decoded (schema/pb->ChangelogEvent bytes)]
      (is (= "bnk.changelog.6:tier:micro" (:dedup-key decoded))))))
