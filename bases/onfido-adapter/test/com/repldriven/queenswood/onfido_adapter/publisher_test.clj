(ns com.repldriven.queenswood.onfido-adapter.publisher-test
  (:require
    [com.repldriven.queenswood.onfido-adapter.publisher :as SUT]

    [clojure.test :refer [deftest is testing]]))

(defn- report
  [name result & {:as extra}]
  (merge {:name name :status "complete" :result result} extra))

(def ^:private document
  (report "document" "clear"
          :sub_result "clear"
          :properties {:first_name "Arthur"
                       :last_name "Dent"
                       :date_of_birth "1952-03-11"
                       :document_type "passport"
                       :issuing_country "GBR"}))

(defn- aml
  [sanction pep]
  (report "watchlist_aml"
          (if (= "clear" sanction pep) "clear" "consider")
          :breakdown {:sanction {:result sanction}
                      :politically_exposed_person {:result pep}}))

(defn- read-run
  [status reports]
  {:run {:id "run-1" :status status}
   :run-name "Arthur Dent"
   :bank-id "bnk.1"
   :verification-id "idv.1"
   :reports reports})

(defn- data
  [status reports]
  (:data (SUT/->idv-evidence (read-run status reports))))

(deftest a-passing-run-test
  (let [evidence (SUT/->idv-evidence
                  (read-run
                   "approved"
                   [document (report "facial_similarity_motion" "clear")
                    (report "proof_of_address" "clear"
                            :properties {:document_type "utility_bill"})
                    (aml "clear" "clear")]))]
    (testing "it names the verification, deduplicated on the run"
      (is (= "idv-evidence" (:event-name evidence)))
      (is (= "run-1:completed" (:dedup-key evidence))))
    (testing "every report is evidence, the document graded on its name"
      (is (= {:bank-id "bnk.1"
              :verification-id "idv.1"
              :cancelled false
              :document {:outcome :idv-evidence-outcome-passed
                         :name-match :idv-name-match-match
                         :document-type "passport"
                         :issuing-country "GBR"}
              :liveness {:outcome :idv-evidence-outcome-passed}
              :address {:outcome :idv-evidence-outcome-passed
                        :document-type "utility_bill"}
              :screening {:sanctions :idv-sanctions-outcome-clear :pep false}}
             (:data evidence))))))

(deftest name-match-test
  (testing "someone else's document is graded no match"
    (is (= :idv-name-match-no-match
           (get-in (data
                    "approved"
                    [(assoc-in document [:properties :first_name] "Trillian")])
                   [:document :name-match])))))

(deftest document-sub-result-test
  (testing "a caution goes to review, a suspected document fails"
    (is (= :idv-evidence-outcome-review
           (get-in (data "review" [(assoc document :sub_result "caution")])
                   [:document :outcome])))
    (is (= :idv-evidence-outcome-failed
           (get-in
            (data "declined"
                  [(assoc document :result "consider" :sub_result "suspected")])
            [:document :outcome])))))

(deftest liveness-failed-test
  (is (= {:outcome :idv-evidence-outcome-failed}
         (:liveness (data "declined"
                          [document
                           (report "facial_similarity_motion" "consider")])))))

(deftest screening-test
  (testing "a sanctions match on a declined run is a hit"
    (is (= :idv-sanctions-outcome-hit
           (get-in (data "declined" [(aml "consider" "clear")])
                   [:screening :sanctions]))))
  (testing "one on a run left for review is a possible match"
    (is (= :idv-sanctions-outcome-possible-match
           (get-in (data "review" [(aml "consider" "clear")])
                   [:screening :sanctions]))))
  (testing "a PEP match is a PEP"
    (is (true? (get-in (data "review" [(aml "clear" "consider")])
                       [:screening :pep])))))

(deftest abandoned-run-test (is (true? (:cancelled (data "abandoned" [])))))

(deftest no-evidence-test
  (testing "a run with no finished report reports nothing"
    (is (nil? (SUT/->idv-evidence (read-run "approved" [])))))
  (testing "nor does one whose tags name no verification"
    (is (nil? (SUT/->idv-evidence (assoc (read-run "approved" [document])
                                         :verification-id
                                         nil))))))
