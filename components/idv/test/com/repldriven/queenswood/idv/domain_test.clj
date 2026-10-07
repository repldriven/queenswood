(ns com.repldriven.queenswood.idv.domain-test
  "The treatment table row by row, evidence merged in any order and
  redelivered, the refusals opening a session can meet — decided
  against the platform policy as bootstrap seeds it — and a session's
  hand-off, reported again."
  (:require
    [com.repldriven.queenswood.idv.domain :as SUT]

    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private platform
  (:platform (env/config "classpath:idv/criteria-test.yml" :test)))

(def ^:private declaration
  (:idv-provider (env/config "classpath:idv/criteria-test.yml" :test)))

(def ^:private document
  {:outcome :idv-evidence-outcome-passed :name-match :idv-name-match-match})

(def ^:private passed {:outcome :idv-evidence-outcome-passed})

(def ^:private failed {:outcome :idv-evidence-outcome-failed})

(def ^:private clear {:sanctions :idv-sanctions-outcome-clear :pep false})

(def ^:private everything
  {:document document :liveness passed :address passed :screening clear})

(defn- decide
  ([evidence] (decide evidence [platform]))
  ([evidence policies]
   (SUT/decide {:status :idv-status-pending :evidence evidence} policies)))

(defn- state
  [evidence criterion]
  (some (fn [c]
          (when (= criterion (or (:verification c) (:screening c)))
            (:state c)))
        (:criteria (decide evidence))))

(deftest identity-test
  (testing "a passed document establishes identity"
    (is (= :idv-criterion-state-established
           (state everything :idv-verification-identity))))
  (testing "a document in review puts the IDV in review"
    (is (= :idv-status-in-review
           (:status (decide (assoc-in everything
                             [:document :outcome]
                             :idv-evidence-outcome-review))))))
  (testing "a failed document rejects"
    (is (= :idv-status-rejected
           (:status (decide (assoc-in everything
                             [:document :outcome]
                             :idv-evidence-outcome-failed)))))))

(deftest liveness-test
  (testing "passed liveness establishes it"
    (is (= :idv-criterion-state-established
           (state everything :idv-verification-liveness))))
  (testing "failed liveness rejects"
    (is (= :idv-status-rejected
           (:status (decide (assoc everything :liveness failed)))))))

(deftest claimed-identity-test
  (testing "a matching name establishes it"
    (is (= :idv-criterion-state-established
           (state everything :idv-verification-claimed-identity))))
  (testing "a close match puts the IDV in review"
    (is (= :idv-status-in-review
           (:status (decide (assoc-in everything
                             [:document :name-match]
                             :idv-name-match-close-match))))))
  (testing "someone else's name rejects"
    (is (= :idv-status-rejected
           (:status (decide (assoc-in everything
                             [:document :name-match]
                             :idv-name-match-no-match))))))
  (testing "a document with no name graded leaves it outstanding"
    (is (= :idv-criterion-state-outstanding
           (state (update everything :document dissoc :name-match)
                  :idv-verification-claimed-identity)))))

(deftest address-test
  (testing "a passed address document establishes it"
    (is (= :idv-criterion-state-established
           (state everything :idv-verification-address))))
  (testing "a failed address document rejects"
    (is (= :idv-status-rejected
           (:status (decide (assoc everything :address failed)))))))

(deftest sanctions-test
  (testing "clear establishes it"
    (is (= :idv-criterion-state-established
           (state everything :idv-screening-sanctions))))
  (testing "a possible match puts the IDV in review"
    (is (= :idv-status-in-review
           (:status (decide (assoc-in everything
                             [:screening :sanctions]
                             :idv-sanctions-outcome-possible-match))))))
  (testing "a hit rejects"
    (is (= :idv-status-rejected
           (:status (decide (assoc-in everything
                             [:screening :sanctions]
                             :idv-sanctions-outcome-hit)))))))

(deftest pep-test
  (testing "not a PEP establishes it"
    (is (= :idv-criterion-state-established
           (state everything :idv-screening-pep))))
  (testing "a PEP puts the IDV in review"
    (is (= :idv-status-in-review
           (:status (decide (assoc-in everything [:screening :pep] true)))))))

(deftest acceptance-test
  (testing "everything established accepts"
    (is (= :idv-status-accepted (:status (decide everything)))))
  (testing "a criterion a deny requires still outstanding keeps it pending"
    (is (= :idv-status-pending
           (:status (decide (dissoc everything :screening))))))
  (testing "a criterion no deny requires does not hold it up"
    (is (= :idv-status-accepted
           (:status (decide {:document document :liveness passed}
                            [{:enabled true
                              :capabilities
                              [{:effect :effect-allow
                                :kind {:idv {:action
                                             :idv-action-accept}}}]}])))))
  (testing "walking away fails the IDV"
    (is (= :idv-status-failed
           (:status (decide {:document document :cancelled true})))))
  (testing "a reject outweighs walking away"
    (is (= :idv-status-rejected
           (:status (decide {:liveness failed :cancelled true}))))))

(deftest merge-evidence-test
  (let [reports [{:document document :liveness passed} {:address passed}
                 {:screening clear}]
        merged (fn [order] (reduce SUT/merge-evidence nil (map reports order)))]
    (testing "evidence merged in any order decides the same way"
      (is (= (merged [0 1 2]) (merged [2 0 1]) (merged [1 2 0])))
      (is (= :idv-status-accepted (:status (decide (merged [2 1 0]))))))
    (testing "a later report of a kind replaces the earlier"
      (is (= failed
             (:address (SUT/merge-evidence {:address passed}
                                           {:address failed})))))))

(deftest apply-evidence-test
  (let [idv {:status :idv-status-pending :verification-id "idv.1"}
        applied (SUT/apply-evidence idv everything [platform])]
    (testing "evidence that establishes everything accepts the IDV"
      (is (= :idv-status-accepted (:status applied)))
      (is (= 6 (count (:criteria applied)))))
    (testing "redelivered evidence decides the same way"
      (let [again (SUT/apply-evidence (assoc idv :evidence everything)
                                      everything
                                      [platform])]
        (is (= (:criteria applied) (:criteria again)))
        (is (= (:status applied) (:status again)))))
    (testing "an IDV already decided takes no more evidence"
      (let [r (SUT/apply-evidence applied everything [platform])]
        (is (error/rejection? r))
        (is (= :idv/invalid-status (error/kind r)))))
    (testing "an IDV in review stays in review on evidence that decides nothing"
      (is (= :idv-status-in-review
             (:status (SUT/apply-evidence
                       (assoc idv :status :idv-status-in-review)
                       {:address passed}
                       [platform])))))))

(deftest check-open-session-test
  (let [idv {:status :idv-status-pending :verification-id "idv.1"}
        data {:channel "web"
              :return-url "https://app.example/back"
              :email "a@example.com"}]
    (testing "a pending IDV opens a session"
      (is (not (error/anomaly?
                (SUT/check-open-session idv declaration data [platform] 0)))))
    (testing "an IDV that is not pending is refused"
      (is (= :idv/invalid-status
             (error/kind (SUT/check-open-session
                          (assoc idv :status :idv-status-accepted)
                          declaration
                          data
                          [platform]
                          0)))))
    (testing "a channel the provider does not declare is refused"
      (is (= :idv/unsupported-channel
             (error/kind (SUT/check-open-session
                          idv
                          (assoc declaration :channels ["web"])
                          (assoc data :channel "mobile")
                          [platform]
                          0)))))
    (testing "no email where the provider needs one is refused"
      (is (= :idv/missing-email
             (error/kind (SUT/check-open-session idv
                                                 declaration
                                                 (dissoc data :email)
                                                 [platform]
                                                 0)))))
    (testing "a bank past its daily limit is refused"
      (is (error/anomaly?
           (SUT/check-open-session idv declaration data [platform] 100000))))))

(deftest ready-session-test
  (let [opening {:session-id "ses.1" :status :idv-session-status-opening}
        ready
        (SUT/ready-session opening "https://idv.test/run/1" 1789000000000)]
    (testing "an opening session takes the hand-off and becomes ready"
      (is (= :idv-session-status-ready (:status ready)))
      (is (= "https://idv.test/run/1" (get-in ready [:hand-off :url]))))
    (testing "a report of the hand-off it already holds changes nothing"
      (is (nil?
           (SUT/ready-session ready "https://idv.test/run/1" 1789000000000))))
    (testing "a fresh hand-off replaces the one it holds"
      (is (= "https://idv.test/run/2"
             (get-in
              (SUT/ready-session ready "https://idv.test/run/2" 1789000600000)
              [:hand-off :url]))))
    (testing "a completed session takes none"
      (is (nil? (SUT/ready-session
                 (assoc ready :status :idv-session-status-completed)
                 "https://idv.test/run/3"
                 1789001200000))))))
