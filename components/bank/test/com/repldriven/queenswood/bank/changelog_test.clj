(ns com.repldriven.queenswood.bank.changelog-test
  (:require
    [com.repldriven.queenswood.bank.changelog :as changelog]

    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]

    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))

;; Loaded the same way `changelog.clj` loads it — from the classpath
;; rather than the injected serde — so the assertion below reads the
;; payload back through the schema that wrote it.
(def ^:private tier-schema
  (delay (avro/json->schema
          (slurp (io/resource "schemas/banks/bank-tier-changed.avsc.json")))))

(deftest changelog-carries-the-shared-envelope-test
  (testing
    "a status transition serialises as a ChangelogEvent the
           generic relay can decode without knowing this domain"
    (let [bytes (changelog/status-changed {:bank-id "bnk.changelog.1"
                                           :status-before :bank-status-test
                                           :status-after :bank-status-live})
          decoded (schema/pb->ChangelogEvent bytes)]
      (is (= "bank-status-changed" (:event-name decoded)))
      (is (= "bnk.changelog.1:bank-status-live" (:dedup-key decoded)))
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
      (is (= "bnk.changelog.3:bank-status-test" (:dedup-key decoded))))))

(deftest tier-change-is-its-own-event-test
  (testing
    "a tier transition serialises under its own event name, and
           its dedup key carries a `tier` discriminator so it cannot
           collide with a status key for the same bank"
    (let [bytes (changelog/tier-changed
                 {:bank-id "bnk.x" :tier-before "micro" :tier-after "growth"})
          decoded (schema/pb->ChangelogEvent bytes)]
      (is (= "bank-tier-changed" (:event-name decoded)))
      (is (= "bnk.x:tier:growth" (:dedup-key decoded)))
      (is (= "bnk.x" (:causation-id decoded)))
      (is (= {:bank-id "bnk.x" :tier-before "micro" :tier-after "growth"}
             (avro/deserialize-same @tier-schema (:payload decoded)))
          "the Avro payload carries the bank and both tiers")
      (is (not= (:dedup-key (schema/pb->ChangelogEvent (changelog/status-changed
                                                        {:bank-id "bnk.x"
                                                         :status-after
                                                         :bank-status-live})))
                (:dedup-key decoded))
          "a status key and a tier key for one bank are disjoint"))))

(deftest bank-creation-has-no-tier-before-test
  (testing "a newly created bank has no source tier"
    (let [bytes (changelog/tier-changed {:bank-id "bnk.y" :tier-after "micro"})
          decoded (schema/pb->ChangelogEvent bytes)]
      (is (= "bnk.y:tier:micro" (:dedup-key decoded))))))
