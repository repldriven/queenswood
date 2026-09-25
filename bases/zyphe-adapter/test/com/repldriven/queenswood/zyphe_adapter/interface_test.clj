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
  [event-id flow-status verification-id]
  {:id event-id
   :type "flow.completed"
   :apiVersion "2026-08-26"
   :createdAt "2026-09-25T12:00:00Z"
   :source {:organizationId "org-1"
            :flowId "flow-1"
            :flowResultId "result-1"}
   :flow {:status flow-status
          :slug "onboarding"
          :customData {:bankId "bnk.test-001"
                       :verificationId verification-id}}})

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
                      :event-name "idv-completed"
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
       (testing "a signed completed run is recorded as accepted"
         (nom-test> [res (deliver-event (event "evt-1" "COMPLETED" "idv.001"))
                     _ (is (= 200 (:status res)))
                     body (http/res->edn res)
                     _ (is (true? (:received body)))])
         (is (recorded? config "idv.001:ACCEPTED")))
       (testing "a redelivered event is acknowledged again"
         (nom-test> [res (deliver-event (event "evt-1" "COMPLETED" "idv.001"))
                     _ (is (= 200 (:status res)))]))
       (testing "review then rejection records both"
         (nom-test> [res (deliver-event (event "evt-2" "REVIEW" "idv.002"))
                     _ (is (= 200 (:status res)))
                     res (deliver-event (event "evt-3" "REJECTED" "idv.002"))
                     _ (is (= 200 (:status res)))])
         (is (recorded? config "idv.002:IN_REVIEW"))
         (is (recorded? config "idv.002:REJECTED")))
       (testing "a run still processing is acknowledged and not recorded"
         (nom-test> [res (deliver-event (event "evt-4" "PROCESSING" "idv.003"))
                     _ (is (= 200 (:status res)))])
         (is (not (recorded? config "idv.003:ACCEPTED"))))
       (testing "a delivery signed with another secret is refused"
         (nom-test> [res (deliver-event (event "evt-5" "COMPLETED" "idv.004")
                                        (apply str (repeat 64 "a")))
                     _ (is (= 401 (:status res)))])
         (is (not (recorded? config "idv.004:ACCEPTED"))))
       (testing "an unsigned delivery is refused"
         (nom-test> [res (http/request
                          {:method :post
                           :url (str *base-url* zyphe-webhook/path)
                           :headers {"Content-Type" "application/json"}
                           :body (json/write-str
                                  (event "evt-6" "COMPLETED" "idv.005"))})
                     _ (is (= 401 (:status res)))]))))))
