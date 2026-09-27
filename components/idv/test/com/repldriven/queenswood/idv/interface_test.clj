(ns com.repldriven.queenswood.idv.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.idv.commands :as commands]
    [com.repldriven.queenswood.idv.interface :as SUT]

    [com.repldriven.queenswood.idv-query.interface :as idv-query]
    [com.repldriven.queenswood.person-identification.interface :as
     person-identification]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error :refer [nom->]]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(deftest unknown-command-test
  (testing "dispatch rejects command names not in the handler registry"
    (let [result (#'commands/dispatch
                  {:schemas {}}
                  {:command "unknown-idv-command" :payload nil})]
      (is (error/rejection? result))
      (is (= :idv/unknown-command (error/kind result))))))

(def ^:private criteria (env/config "classpath:idv/criteria-test.yml" :test))

(deftest unmet-criteria-test
  (let [{:keys [platform micro idv-provider]} criteria]
    (testing "the platform policy requires all six of a person"
      (is (= [:idv-verification-identity :idv-verification-liveness
              :idv-verification-claimed-identity :idv-verification-address
              :idv-screening-sanctions :idv-screening-pep]
             (SUT/unmet-criteria [platform] {:verifies [] :screens []}))))
    (testing "the micro tier adds nothing to the platform floor"
      (is (= (SUT/unmet-criteria [platform] {:verifies [] :screens []})
             (SUT/unmet-criteria [platform micro] {:verifies [] :screens []}))))
    (testing "the deployed provider meets the platform and micro policies"
      (is (= [] (SUT/unmet-criteria [platform micro] idv-provider))))
    (testing "a provider that does not verify an address leaves it unmet"
      (is (= [:idv-verification-address]
             (SUT/unmet-criteria [platform]
                                 (update idv-provider
                                         :verifies
                                         (fn [vs] (remove #{"address"} vs)))))))
    (testing "check-criteria rejects naming what is unmet"
      (let [r (SUT/check-criteria [platform]
                                  (assoc idv-provider :screens ["sanctions"]))]
        (is (error/rejection? r))
        (is (= :idv/unsupported-criteria (error/kind r)))
        (is (= [:idv-screening-pep] (:unmet (error/payload r))))
        (is (= "The identity provider cannot establish pep"
               (:message (error/payload r))))))
    (testing "check-criteria passes a provider that meets them"
      (is (nil? (SUT/check-criteria [platform micro] idv-provider))))))

(deftest criteria-check-test
  (testing "starts when the provider meets the platform policy"
    (with-test-system [sys "classpath:idv/criteria-test.yml"]
                      (is (some? (system/instance sys
                                                  [:idv :criteria-check])))))
  (testing "refuses to start when the provider does not verify an address"
    (let [result (nom-> (env/config "classpath:idv/criteria-unmet-test.yml"
                                    :test)
                        system/defs
                        system/start)]
      (is (error/anomaly? result))
      (is (some (fn [e] (= :idv/unsupported-criteria (:kind (ex-data e))))
                (take-while some?
                            (iterate ex-cause
                                     (:exception (error/payload result)))))))))

(def ^:private test-bank-id "bnk_test_idv")

(defn- send-command
  [proc schemas command-name data]
  (let [payload (avro/serialize (get schemas command-name) data)]
    (if (error/anomaly? payload)
      payload
      (processor/process proc {:command command-name :payload payload}))))

(defn- decode-payload
  [schemas schema-name result]
  (avro/deserialize-same (get schemas schema-name) (:payload result)))

(defn- test-initiate-idv
  [proc schemas]
  (testing "initiate creates IDV with pending status"
    (let [payload {:bank-id test-bank-id :party-id "pty.test-party-id"}]
      (nom-test> [result (send-command proc schemas "initiate-idv" payload)
                  _
                  (is (= "ACCEPTED" (:status result)))
                  decoded
                  (decode-payload schemas "idv" result)
                  _
                  (is (some? (:verification-id decoded)))
                  _
                  (is (= "pty.test-party-id" (:party-id decoded)))
                  _
                  (is (= :idv-status-pending (:status decoded)))
                  _
                  (is (nil? (:completed-at decoded)))]))))

;; pending → accepted is no longer driven by an unconditional flip
;; in this brick; it now flows through the IDV-provider adapter
;; (bank-onfido-adapter) and the message-bus event handler in
;; `bank-idv.events`. The full chain is exercised by the monolith
;; integration test `idv_test.clj`.

(deftest process-idv-test
  (with-test-system [sys "classpath:idv/application-test.yml"]
                    (let [proc (system/instance sys [:idv :processor])
                          schemas (system/instance sys [:avro :serde])]
                      (test-initiate-idv proc schemas))))

(defn- send-event
  [event-proc schemas event-name data]
  (let [payload (avro/serialize (get schemas event-name) data)]
    (if (error/anomaly? payload)
      payload
      (processor/process event-proc {:event event-name :payload payload}))))

(defn- initiate
  [proc schemas]
  (let [result (send-command proc
                             schemas
                             "initiate-idv"
                             {:bank-id test-bank-id
                              :party-id (utility/generate-id "pty")})]
    (decode-payload schemas "idv" result)))

(defn- complete
  [event-proc schemas verification-id status]
  (send-event event-proc
              schemas
              "idv-completed"
              {:bank-id test-bank-id
               :verification-id verification-id
               :status status}))

(deftest idv-completed-in-review-and-failed-test
  (with-test-system
   [sys "classpath:idv/application-test.yml"]
   (let [proc (system/instance sys [:idv :processor])
         event-proc (system/instance sys [:idv :event-processor])
         schemas (system/instance sys [:avro :serde])]
     (testing "IN_REVIEW moves a pending IDV to in-review, awaiting resolution"
       (let [{:keys [verification-id]} (initiate proc schemas)
             updated (complete event-proc schemas verification-id "IN_REVIEW")]
         (is (= :idv-status-in-review (:status updated)))
         (is (not (pos? (:completed-at updated))))))
     (testing "IN_REVIEW then ACCEPTED still resolves the IDV"
       (let [{:keys [verification-id]} (initiate proc schemas)
             _ (complete event-proc schemas verification-id "IN_REVIEW")
             updated (complete event-proc schemas verification-id "ACCEPTED")]
         (is (= :idv-status-accepted (:status updated)))
         (is (some? (:completed-at updated)))))
     (testing "FAILED marks a pending IDV as retryable, not terminal"
       (let [{:keys [verification-id]} (initiate proc schemas)
             updated (complete event-proc schemas verification-id "FAILED")]
         (is (= :idv-status-failed (:status updated)))
         (is (some? (:completed-at updated)))))
     (testing
       "a FAILED IDV does not resolve in place — retrying means a new verification"
       (let [{:keys [verification-id]} (initiate proc schemas)
             _ (complete event-proc schemas verification-id "FAILED")
             skipped (complete event-proc schemas verification-id "ACCEPTED")]
         (is (nil? skipped))))
     (testing
       "a late duplicate webhook against a terminal IDV is skipped, not applied"
       (let [{:keys [verification-id]} (initiate proc schemas)
             _ (complete event-proc schemas verification-id "ACCEPTED")
             skipped (complete event-proc schemas verification-id "IN_REVIEW")]
         (is (nil? skipped))
         (nom-test> [result (send-command proc
                                          schemas
                                          "get-idv"
                                          {:bank-id test-bank-id
                                           :verification-id verification-id})
                     decoded (decode-payload schemas "idv" result)
                     _ (is (= :idv-status-accepted (:status decoded)))]))))))

(def ^:private everything
  {:document {:outcome :idv-evidence-outcome-passed
              :given-names "Arthur"
              :family-name "Dent"
              :date-of-birth "1952-03-11"}
   :liveness {:outcome :idv-evidence-outcome-passed}
   :address {:outcome :idv-evidence-outcome-passed}
   :screening {:sanctions :idv-sanctions-outcome-clear :pep false}})

(deftest session-and-evidence-test
  (with-test-system
   [sys "classpath:idv/application-test.yml"]
   (let [proc (system/instance sys [:idv :processor])
         event-proc (system/instance sys [:idv :event-processor])
         schemas (system/instance sys [:avro :serde])
         config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :meta-store])}
         party-id (utility/generate-id "pty")
         saved (person-identification/save-person-identification
                config
                (person-identification/new-person-identification
                 {:given-name "Arthur"
                  :family-name "Dent"
                  :date-of-birth 19520311
                  :nationality "GB"
                  :address {:building-number "155"
                            :street "Country Lane"
                            :town "Cottington"
                            :postcode "CT12 4XY"
                            :country "GBR"}}
                 party-id))
         _ (is (not (error/anomaly? saved)))
         {:keys [verification-id]} (decode-payload schemas
                                                   "idv"
                                                   (send-command
                                                    proc
                                                    schemas
                                                    "initiate-idv"
                                                    {:bank-id test-bank-id
                                                     :party-id party-id}))
         open (fn []
                (send-command proc
                              schemas
                              "open-idv-session"
                              {:bank-id test-bank-id
                               :party-id party-id
                               :channel "web"
                               :return-url "https://app.example/back"
                               :email "a.dent@example.com"}))]
     (testing "opening a session records it, opening"
       (nom-test> [result (open)
                   _ (is (= "ACCEPTED" (:status result)))
                   session (decode-payload schemas "idv-session" result)
                   _ (is (= :idv-session-status-opening (:status session)))
                   _ (is (= verification-id (:verification-id session)))
                   stored (idv-query/get-session config
                                                 test-bank-id
                                                 (:session-id session))
                   _ (is (= :idv-session-channel-web (:channel stored)))
                   opened (idv-query/count-sessions-on config
                                                       test-bank-id
                                                       (utility/today))
                   _ (is (= 1 opened))
                   _ (send-event event-proc
                                 schemas
                                 "idv-session-opened"
                                 {:bank-id test-bank-id
                                  :verification-id verification-id
                                  :session-id (:session-id session)
                                  :url "https://verify.example/flow/x"
                                  :expires-at (+ (utility/now) 60000)})
                   ready (idv-query/get-session config
                                                test-bank-id
                                                (:session-id session))
                   _ (is (= :idv-session-status-ready (:status ready)))
                   view (idv-query/session ready (utility/now))
                   _ (is (= "https://verify.example/flow/x"
                            (get-in view [:hand-off :url])))
                   expired (idv-query/session ready (+ (utility/now) 120000))
                   _ (is (= :idv-session-status-expired (:status expired))
                         "a lapsed hand-off reads expired")
                   _ (is (nil? (:hand-off expired)))
                   decided (send-event event-proc
                                       schemas
                                       "idv-evidence"
                                       (assoc everything
                                              :bank-id test-bank-id
                                              :verification-id verification-id
                                              :cancelled false))
                   _ (is (= :idv-status-accepted (:status decided)))
                   completed (idv-query/get-session config
                                                    test-bank-id
                                                    (:session-id session))
                   _ (is (= :idv-session-status-completed (:status completed))
                         "the session completes once the IDV decides")
                   _ (is (nil? (:url (:hand-off completed)))
                         "and its hand-off is dropped")
                   redelivered (send-event event-proc
                                           schemas
                                           "idv-evidence"
                                           (assoc everything
                                                  :bank-id test-bank-id
                                                  :verification-id
                                                  verification-id
                                                  :cancelled false))
                   _ (is (nil? redelivered) "redelivery is a no-op")]))
     (testing "the verification lists every criterion as established"
       (nom-test> [idv (idv-query/get-idv config test-bank-id verification-id)
                   policies (policy/get-effective-policies config
                                                           {:bank-id
                                                            test-bank-id})
                   view (idv-query/verification idv policies)
                   _ (is (= :idv-status-accepted (:status view)))
                   _ (is (= #{:idv-criterion-state-established}
                            (set (map :state (:criteria view)))))
                   _ (is (= 6 (count (:criteria view))))]))
     (testing "a decided IDV opens no more sessions"
       (let [result (open)]
         (is (error/rejection? result))
         (is (= :idv/invalid-status (error/kind result))))))))
