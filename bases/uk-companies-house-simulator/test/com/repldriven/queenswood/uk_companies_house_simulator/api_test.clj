(ns com.repldriven.queenswood.uk-companies-house-simulator.api-test
  (:refer-clojure :exclude [get])
  (:require
    [com.repldriven.queenswood.uk-companies-house-simulator.system]

    [com.repldriven.queenswood.uk-companies-house-simulator.api :as api]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))

(def ^:dynamic *base-url* "http://localhost:{PORT}")

(defn- get
  [path]
  (http/request {:method :get :url (str *base-url* path)}))

(deftest openapi-test
  (with-test-system
   [sys
    ["classpath:uk-companies-house-simulator/application-test.yml"
     #(assoc-in % [:system/defs :server :handler] api/app)]]
   (let [jetty (system/instance sys [:server :jetty-adapter])]
     (binding [*base-url* (server/http-local-url jetty)]
       (testing "GET /openapi.json returns a valid OpenAPI spec"
         (nom-test> [res (get "/openapi.json")
                     _ (is (= 200 (:status res)))
                     spec (http/res->edn res)
                     _ (is (= "3.1.0" (:openapi spec)))]))))))

(deftest get-company-test
  (with-test-system
   [sys
    ["classpath:uk-companies-house-simulator/application-test.yml"
     #(assoc-in % [:system/defs :server :handler] api/app)]]
   (let [jetty (system/instance sys [:server :jetty-adapter])]
     (binding [*base-url* (server/http-local-url jetty)]
       (testing "GET /company/{number} returns a known fixture"
         (nom-test> [res (get "/company/SC998137")
                     _ (is (= 200 (:status res)))
                     body (http/res->edn res)
                     _ (is (= "SC998137" (:company_number body)))
                     _ (is (= "SIRIUS CYBERNETICS CORPORATION LTD"
                              (:company_name body)))
                     _ (is (= "active" (:company_status body)))
                     _ (is (= "United Kingdom"
                              (get-in body
                                      [:registered_office_address :country])))]))
       (testing "GET /company/{number} returns 404 for unknown numbers"
         (nom-test> [res (get "/company/99999999")
                     _ (is (= 404 (:status res)))
                     body (http/res->edn res)
                     _ (is (= "company-profile-not-found"
                              (:error (first (:errors body)))))]))))))
