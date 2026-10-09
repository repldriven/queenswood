(ns com.repldriven.queenswood.zyphe-adapter.interface-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.zyphe-adapter.interface :as SUT]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]
    [com.repldriven.queenswood.zyphe-relay.interface :as relay]
    [com.repldriven.queenswood.zyphe-webhook.interface :as zyphe-webhook]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.message-bus.interface]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]])
  (:import
    (java.nio.charset StandardCharsets)))

(def ^:dynamic *base-url* "http://localhost:{PORT}")

(def ^:private secret
  "9f2b7c1d4e6a8035b1c9d2e4f6a80351d7e9b2c4f6a803517d9e2b4c6f8a0351")

(defn- event
  [event-id type flow-status verification-id data]
  {:id event-id
   :type type
   :apiVersion "2026-08-26"
   :createdAt "2026-09-25T12:00:00Z"
   :source {:organizationId "org-1"
            :flowId "flow-1"
            :flowResultId "result-1"}
   :flow {:status flow-status
          :slug "onboarding"
          :customData {:partyId "pty.test-001"
                       :bankId "bnk.test-001"
                       :verificationId verification-id}}
   :data data})

(defn- document-event
  [event-id verification-id]
  (event event-id
         "verification.dv.completed"
         "PROCESSING"
         verification-id
         {:dv {:id "dv-1" :status "PASSED" :reasons []}
          :additionalData {:firstName "Arthur"
                           :lastName "Dent"
                           :dateOfBirth "1952-03-11"}}))

(defn- deliver-event
  ([body] (deliver-event body secret))
  ([body signing-secret]
   (let [raw (.getBytes ^String (json/write-str body) StandardCharsets/UTF_8)
         header (zyphe-webhook/sign signing-secret
                                    (quot (utility/now) 1000)
                                    raw)]
     (http/request {:method :post
                    :url (str *base-url* zyphe-webhook/path)
                    :headers {"Content-Type" "application/json"
                              "X-Signature" header}
                    :body raw}))))

(defn- recorded?
  "True when the adapter already wrote an outbox event under
  `dedup-key`: saving another under it is refused as a duplicate."
  [config dedup-key]
  (relay/uniqueness-violation?
   (relay/save-event config
                     {:outbox-id (str (utility/uuidv7))
                      :dedup-key dedup-key
                      :event-name "idv-evidence-received"
                      :payload (.getBytes "probe")
                      :created-at (utility/now)})))

(defn- recorded-evidence
  "The idv-evidence the adapter wrote to its outbox under `dedup-key`,
  decoded, and the payload's bytes as text."
  [config serde dedup-key]
  (let [event (fdb/transact config
                            (fn [txn]
                              (some-> (fdb/query-record
                                       (fdb/open txn "zyphe-outbox")
                                       "ZypheOutboxEvent"
                                       "dedup_key"
                                       dedup-key
                                       {:index "ZypheOutboxEvent_by_dedup_key"})
                                      schema/pb->ZypheOutboxEvent)))
        payload (:payload event)]
    {:evidence (avro/deserialize-same (get serde "idv-evidence-received")
                                      payload)
     :text (String. ^bytes payload StandardCharsets/UTF_8)}))

(deftest webhook-test
  (with-test-system
   [sys
    ["classpath:zyphe-adapter/application-test.yml"
     #(assoc-in % [:system/defs :server :handler] SUT/app)]]
   (let [jetty (system/instance sys [:server :jetty-adapter])
         config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :meta-store])}]
     (binding [*base-url* (server/http-local-url jetty)]
       (testing "a signed document result is recorded as evidence"
         (nom-test> [res (deliver-event (document-event "evt-1" "idv.001"))
                     _ (is (= 200 (:status res)))
                     body (http/res->edn res)
                     _ (is (true? (:received body)))])
         (is (recorded? config "evt-1")))
       (testing "nothing the document says is recorded"
         (nom-test> [res (deliver-event (document-event "evt-7" "idv.007"))
                     _ (is (= 200 (:status res)))])
         (let [{:keys [evidence text]} (recorded-evidence
                                        config
                                        (system/instance sys [:avro :serde])
                                        "evt-7")]
           (is (= :idv-evidence-outcome-passed
                  (get-in evidence [:document :outcome])))
           (is (not-any? (fn [read] (.contains ^String text read))
                         ["Arthur" "Dent" "1952-03-11"]))))
       (testing "a redelivered event is acknowledged again"
         (nom-test> [res (deliver-event (document-event "evt-1" "idv.001"))
                     _ (is (= 200 (:status res)))]))
       (testing "an AML result is recorded as evidence"
         (nom-test> [res (deliver-event
                          (event "evt-2"
                                 "verification.aml.produced" "REVIEW"
                                 "idv.002" {:aml {:status "ESCALATED"
                                                  :hasSanctions false
                                                  :hasPep true}}))
                     _ (is (= 200 (:status res)))])
         (is (recorded? config "evt-2")))
       (testing "a run's completion is acknowledged and not recorded"
         (nom-test> [res (deliver-event (event "evt-3"
                                               "flow.completed" "COMPLETED"
                                               "idv.003" {:identityId "id-1"}))
                     _ (is (= 200 (:status res)))])
         (is (not (recorded? config "evt-3"))))
       (testing "a delivery signed with another secret is refused"
         (nom-test> [res (deliver-event (document-event "evt-5" "idv.004")
                                        (apply str (repeat 64 "a")))
                     _ (is (= 401 (:status res)))])
         (is (not (recorded? config "evt-5"))))
       (testing "an unsigned delivery is refused"
         (nom-test> [res (http/request
                          {:method :post
                           :url (str *base-url* zyphe-webhook/path)
                           :headers {"Content-Type" "application/json"}
                           :body (json/write-str (document-event "evt-6"
                                                                 "idv.005"))})
                     _ (is (= 401 (:status res)))]))))))
