(ns com.repldriven.queenswood.onfido-simulator.api-test
  (:refer-clojure :exclude [get])
  (:require
    [com.repldriven.queenswood.onfido-simulator.system]

    [com.repldriven.queenswood.onfido-simulator.api :as api]

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

(defn- get
  [path]
  (http/request {:method :get :url (str *base-url* path)}))

(defn- delete
  [path]
  (http/request {:method :delete :url (str *base-url* path)}))

(deftest openapi-test
  (with-test-system [sys
                     ["classpath:onfido-simulator/application-test.yml"
                      #(assoc-in % [:system/defs :server :handler] api/app)]]
                    (let [jetty (system/instance sys [:server :jetty-adapter])]
                      (binding [*base-url* (server/http-local-url jetty)]
                        (testing
                          "GET /openapi.json returns a valid OpenAPI spec"
                          (nom-test> [res (get "/openapi.json")
                                      _ (is (= 200 (:status res)))
                                      spec (http/res->edn res)
                                      _ (is (= "3.1.0" (:openapi spec)))]))))))

(deftest applicants-and-checks-test
  (with-test-system
   [sys
    ["classpath:onfido-simulator/application-test.yml"
     #(assoc-in % [:system/defs :server :handler] api/app)]]
   (let [jetty (system/instance sys [:server :jetty-adapter])]
     (binding [*base-url* (server/http-local-url jetty)]
       (let [sample-address {:building_number "100"
                             :street "Main Street"
                             :town "London"
                             :postcode "SW1A 1AA"
                             :country "GBR"}]
         (testing "POST /v3.6/applicants creates an applicant"
           (nom-test> [res (post "/v3.6/applicants"
                                 {:first_name "Arthur"
                                  :last_name "Dent"
                                  :address sample-address})
                       _ (is (= 201 (:status res)))
                       body (http/res->edn res)
                       _ (is (string? (:id body)))
                       _ (is (= "Arthur" (:first_name body)))]))
         (testing "POST /v3.6/checks rejects with 422 when applicant unknown"
           (nom-test> [res (post "/v3.6/checks" {:applicant_id "missing"})
                       _ (is (= 422 (:status res)))]))
         (testing "POST /v3.6/checks succeeds for a known applicant"
           (nom-test> [a (post "/v3.6/applicants"
                               {:first_name "Ford"
                                :last_name "Prefect"
                                :address sample-address})
                       applicant-id (:id (http/res->edn a))
                       res (post "/v3.6/checks" {:applicant_id applicant-id})
                       _ (is (= 201 (:status res)))
                       body (http/res->edn res)
                       _ (is (= applicant-id (:applicant_id body)))
                       _ (is (= "in_progress" (:status body)))])))))))

(deftest webhook-crud-test
  (with-test-system
   [sys
    ["classpath:onfido-simulator/application-test.yml"
     #(assoc-in % [:system/defs :server :handler] api/app)]]
   (let [jetty (system/instance sys [:server :jetty-adapter])]
     (binding [*base-url* (server/http-local-url jetty)]
       (testing "register, list, deregister"
         (nom-test> [res (post "/v3.6/webhooks" {:url "http://test/hook"})
                     _ (is (= 201 (:status res)))
                     body (http/res->edn res)
                     id (:id body)
                     res (get "/v3.6/webhooks")
                     _ (is (= 200 (:status res)))
                     list-body (http/res->edn res)
                     _ (is (some (fn [w] (= id (:id w))) (:webhooks list-body)))
                     res (delete (str "/v3.6/webhooks/" id))
                     _ (is (= 204 (:status res)))]))))))
