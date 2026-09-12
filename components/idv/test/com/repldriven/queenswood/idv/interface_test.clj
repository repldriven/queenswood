(ns com.repldriven.queenswood.idv.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.idv.commands :as commands]
    [com.repldriven.queenswood.idv.interface]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error]
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
