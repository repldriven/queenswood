(ns com.repldriven.queenswood.zyphe-adapter.publisher-test
  (:require
    [com.repldriven.queenswood.zyphe-adapter.publisher :as SUT]

    [clojure.test :refer [deftest is testing]]))

(defn- event
  [type flow-status data]
  {:id "evt-1"
   :type type
   :flow {:status flow-status
          :customData {:bankId "bnk.1" :verificationId "idv.1"}}
   :data data})

(defn- evidence
  [type flow-status data]
  (:data (SUT/->idv-evidence (event type flow-status data))))

(deftest document-test
  (testing "a passed document reports what it says, and liveness"
    (let [ev (evidence "verification.dv.completed"
                       "PROCESSING"
                       {:dv {:status "PASSED" :reasons []}
                        :additionalData {:firstName "Arthur"
                                         :lastName "Dent"
                                         :dateOfBirth "1952-03-11"
                                         :issuingState "GBR"}})]
      (is (= {:outcome :idv-evidence-outcome-passed
              :given-names "Arthur"
              :family-name "Dent"
              :date-of-birth "1952-03-11"
              :document-type nil
              :issuing-country "GBR"}
             (:document ev)))
      (is (= {:outcome :idv-evidence-outcome-passed} (:liveness ev)))
      (is (= "bnk.1" (:bank-id ev)))
      (is (= "idv.1" (:verification-id ev)))))
  (testing "a document failed on liveness reports liveness and no document"
    (let [ev (evidence "verification.dv.failed"
                       "FAILED"
                       {:dv {:status "FAILED"
                             :reasons ["LIVENESS_CHECK_FAILED"]}})]
      (is (nil? (:document ev)))
      (is (= {:outcome :idv-evidence-outcome-failed} (:liveness ev)))))
  (testing "a document failed on anything else reports the document"
    (let [ev (evidence "verification.dv.failed"
                       "FAILED"
                       {:dv {:status "FAILED"
                             :reasons ["FAKE_DOCUMENT_DETECTED"]}})]
      (is (= :idv-evidence-outcome-failed (get-in ev [:document :outcome])))
      (is (nil? (:liveness ev)))))
  (testing "a document in review reports a review"
    (is (= :idv-evidence-outcome-review
           (get-in (evidence "verification.dv.review"
                             "REVIEW"
                             {:dv {:status "REVIEW" :reasons []}})
                   [:document :outcome])))))

(deftest address-test
  (is (= {:outcome :idv-evidence-outcome-failed :document-type "UTILITY_BILL"}
         (:address (evidence "verification.poa.failed"
                             "FAILED"
                             {:poa {:status "FAILED"
                                    :documentType "UTILITY_BILL"}})))))

(deftest screening-test
  (let [screening (fn [aml]
                    (:screening (evidence "verification.aml.produced"
                                          "PROCESSING"
                                          {:aml aml})))]
    (testing "no match is clear"
      (is (= {:sanctions :idv-sanctions-outcome-clear :pep false}
             (screening
              {:hasSanctions false :hasPep false :status "PENDING"}))))
    (testing "a match a moderator approved is clear"
      (is (= :idv-sanctions-outcome-clear
             (:sanctions (screening {:hasSanctions true :status "APPROVED"})))))
    (testing "a match a moderator rejected is a hit"
      (is (= :idv-sanctions-outcome-hit
             (:sanctions (screening {:hasSanctions true :status "REJECTED"})))))
    (testing "an unmoderated match is a possible match"
      (is (= :idv-sanctions-outcome-possible-match
             (:sanctions (screening {:hasSanctions true
                                     :status "ESCALATED"})))))
    (testing "a PEP is reported"
      (is (true? (:pep (screening {:hasSanctions false
                                   :hasPep true
                                   :status "ESCALATED"})))))))

(deftest cancelled-and-silent-test
  (testing "a run the person walked away from reports that"
    (is (true? (:cancelled (evidence "verification.dv.failed"
                                     "CANCELLED"
                                     {:dv {:status "FAILED"}})))))
  (testing "a run's completion reports nothing"
    (is (nil? (SUT/->idv-evidence
               (event "flow.completed" "COMPLETED" {:identityId "id-1"})))))
  (testing "an event naming no verification reports nothing"
    (is (nil? (SUT/->idv-evidence (assoc-in (event "verification.dv.completed"
                                                   "PROCESSING"
                                                   {:dv {:status "PASSED"}})
                                   [:flow :customData]
                                   {}))))))
