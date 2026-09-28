(ns com.repldriven.queenswood.onfido-adapter.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.onfido-adapter.interface :as SUT]

    [com.repldriven.queenswood.onfido-webhook.interface :as onfido-webhook]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.test :refer [deftest is testing]])
  (:import
    (java.nio.charset StandardCharsets)))

(defn- event
  [action]
  {:payload {:resource_type "workflow_run"
             :action action
             :object {:id "run-1" :status "approved"}}})

(defn- deliver-event
  [base-url body token]
  (let [raw (.getBytes ^String (json/write-str body) StandardCharsets/UTF_8)]
    (http/request {:method :post
                   :url (str base-url onfido-webhook/path)
                   :headers (cond-> {"Content-Type" "application/json"}
                                    token
                                    (assoc "X-SHA2-Signature"
                                           (onfido-webhook/sign token raw)))
                   :body raw})))

(deftest webhook-test
  (with-test-system
   [sys
    ["classpath:onfido-adapter/application-test.yml"
     #(assoc-in % [:system/defs :server :handler] SUT/app)]]
   (let [base-url (server/http-local-url (system/instance sys
                                                          [:server
                                                           :jetty-adapter]))]
     (testing "an unsigned delivery is refused"
       (is (= 401
              (:status
               (deliver-event base-url (event "workflow_run.completed") nil)))))
     (testing "one signed with another token is refused"
       (is (= 401
              (:status (deliver-event base-url
                                      (event "workflow_run.completed")
                                      "other-token")))))
     (testing "a signed event naming no finished run is acknowledged"
       (is (= 200
              (:status (deliver-event base-url
                                      (event "workflow_task.completed")
                                      "test-token")))))
     (testing "a finished run Onfido cannot be asked about is not recorded"
       (is (= 500
              (:status (deliver-event base-url
                                      (event "workflow_run.completed")
                                      "test-token"))))))))
