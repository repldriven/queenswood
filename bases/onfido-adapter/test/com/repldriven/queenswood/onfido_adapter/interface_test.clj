(ns com.repldriven.queenswood.onfido-adapter.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.onfido-adapter.interface :as SUT]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(def ^:dynamic *base-url* "http://localhost:{PORT}")

(defn- post
  [path body]
  (http/request {:method :post
                 :url (str *base-url* path)
                 :headers {"Content-Type" "application/json"}
                 :body (json/write-str body)}))

(deftest check-completed-test
  (with-test-system
   [sys
    ["classpath:onfido-adapter/application-test.yml"
     #(assoc-in % [:system/defs :server :handler] SUT/app)]]
   (let [jetty (system/instance sys [:server :jetty-adapter])]
     (binding [*base-url* (server/http-local-url jetty)]
       (testing "POST /webhooks/onfido/check-completed acknowledges 200"
         (nom-test> [res (post "/webhooks/onfido/check-completed"
                               {:payload
                                {:resource_type "check"
                                 :action "check.completed"
                                 :object
                                 {:id "ch.test-001"
                                  :status "complete"
                                  :result "clear"
                                  :completed_at_iso8601 "2026-05-02T12:00:00Z"
                                  :external_id "bnk.test-001|iv.test-001"}}})
                     _ (is (= 200 (:status res)))
                     body (http/res->edn res)
                     _ (is (true? (:received body)))]))))))
