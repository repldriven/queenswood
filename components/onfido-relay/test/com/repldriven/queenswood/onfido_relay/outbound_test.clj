(ns com.repldriven.queenswood.onfido-relay.outbound-test
  "The outbound call's anomaly kinds are the provider-neutral `:idv/*`
  set, not the vendor's name. A kind surfaces as the API's RFC 9457
  `type`, so a second identity provider consuming this channel must not
  change the contract (ADR-0020).

  These pin each arm of the status split and pin that no kind names a
  vendor. They drive `classify` directly rather than faking the HTTP
  layer, because every way of doing that redefines a var for the whole
  JVM and this suite runs in parallel."
  (:require
    [com.repldriven.queenswood.onfido-relay.outbound :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private provider-url "http://provider/v3.6/applicants")

(defn- classifying
  [res]
  (#'SUT/classify provider-url res))

(defn- responding
  [status]
  (classifying {:status status :body "{}"}))

(deftest unreachable-provider-is-unavailable-test
  (testing
    "a transport failure is named for the problem and keeps the
    underlying anomaly as :cause"
    (let [anomaly (classifying (error/fail :http-client/request
                                           {:message "HTTP request failed"}))]
      (is (= :idv/unavailable (error/kind anomaly)))
      (is (some? (:cause (error/payload anomaly)))))))

(deftest upstream-5xx-is-unavailable-test
  (testing "a provider 5xx is retryable and says so"
    (doseq [status [500 502 503]]
      (let [anomaly (responding status)]
        (is (= :idv/unavailable (error/kind anomaly)) (str "status " status))
        (is (= status (:status (error/payload anomaly))))))))

(deftest upstream-429-is-rate-limited-test
  (testing "being throttled is its own kind, distinct from being down"
    (let [anomaly (responding 429)]
      (is (= :idv/rate-limited (error/kind anomaly)))
      (is (= 429 (:status (error/payload anomaly)))))))

(deftest remaining-4xx-keeps-the-call-site-name-test
  (testing
    "a rejected request is not caller-actionable, so it keeps the
    call-site name"
    (doseq [status [400 401 422]]
      (let [anomaly (responding status)]
        (is (= :idv/http (error/kind anomaly)) (str "status " status))
        (is (= status (:status (error/payload anomaly))))))))

(deftest success-passes-the-response-through-test
  (testing "a 2xx returns the response map untouched"
    (let [res (responding 201)]
      (is (not (error/anomaly? res)))
      (is (= 201 (:status res))))))

(deftest missing-status-passes-through-test
  (testing
    "a response with no status is left alone rather than
           tripping the comparisons"
    (is (= {:body "{}"} (classifying {:body "{}"})))))

(deftest no-kind-names-the-vendor-test
  (testing "every kind this fn can raise stays provider-neutral"
    (doseq [status [429 503 400]]
      (let [kind (error/kind (responding status))]
        (is (= "idv" (namespace kind)) (str "kind " kind))))
    (is (= "idv"
           (namespace (error/kind (classifying (error/fail
                                                :http-client/request
                                                {:message
                                                 "HTTP request failed"}))))))))

(def ^:private full
  {:id "wf-full"
   :verifies ["identity" "liveness" "claimed-identity" "address"]
   :screens ["sanctions" "pep"]})

(def ^:private screening
  {:id "wf-screening" :verifies [] :screens ["sanctions"]})

(deftest select-workflow-test
  (testing "the smallest workflow covering what is asked"
    (is (= "wf-screening"
           (:id (SUT/select-workflow [full screening] ["sanctions"]))))
    (is (= "wf-full"
           (:id (SUT/select-workflow [full screening]
                                     ["identity" "sanctions"])))))
  (testing "none where no workflow covers it"
    (is (nil? (SUT/select-workflow [screening] ["address"])))))

(deftest uncovered-test
  (is (= #{"address"}
         (SUT/uncovered [screening]
                        {:verifies ["address"] :screens ["sanctions"]})))
  (is (empty? (SUT/uncovered [full] {:verifies ["address"] :screens ["pep"]}))))

(deftest applicant-test
  (testing "the applicant is the legal name split at its last space"
    (is (= {:first_name "Arthur Philip"
            :last_name "Dent"
            :email "arthur@example.test"}
           (SUT/applicant {:legal-name "Arthur Philip Dent"
                           :email "arthur@example.test"}))))
  (testing "a one-word name is both"
    (is (= {:first_name "Teller" :last_name "Teller"}
           (SUT/applicant {:legal-name "Teller"})))))

(deftest workflow-run-test
  (let [run (SUT/workflow-run {:hand-off-ttl-ms 60000}
                              full
                              "app-1"
                              {:bank-id "bnk.1"
                               :verification-id "idv.1"
                               :party-id "pty.1"
                               :return-url "https://tenant/back"})]
    (testing "the run is tagged with the bank and verification it serves"
      (is (= ["bank:bnk.1" "verification:idv.1"] (:tags run)))
      (is (= "pty.1" (:customer_user_id run))))
    (testing "its link returns the person to the tenant"
      (is (= "https://tenant/back"
             (get-in run [:link :completed_redirect_url])))
      (is (string? (get-in run [:link :expires_at]))))))
