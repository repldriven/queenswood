(ns com.repldriven.queenswood.zyphe-adapter.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.zyphe-adapter.interface :as SUT]

    [com.repldriven.queenswood.zyphe-relay.interface :as relay]
    [com.repldriven.queenswood.zyphe-webhook.interface :as zyphe-webhook]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
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
          :customData {:bankId "bnk.test-001"
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
                      :event-name "idv-evidence"
                      :payload (.getBytes "probe")
                      :created-at (utility/now)})))

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
