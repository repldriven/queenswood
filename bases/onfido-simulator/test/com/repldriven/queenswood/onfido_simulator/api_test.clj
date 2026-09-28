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
                                      _ (is (= "3.2.0" (:openapi spec)))]))))))

(deftest workflow-run-test
  (with-test-system
   [sys
    ["classpath:onfido-simulator/application-test.yml"
     #(assoc-in % [:system/defs :server :handler] api/app)]]
   (let [jetty (system/instance sys [:server :jetty-adapter])]
     (binding [*base-url* (server/http-local-url jetty)]
       (nom-test> [applicant (post "/v3.6/applicants"
                                   {:first_name "Arthur" :last_name "Dent"})
                   _ (is (= 201 (:status applicant)))
                   applicant-id (:id (http/res->edn applicant))
                   refused (post "/v3.6/workflow_runs"
                                 {:workflow_id "wf-1" :applicant_id "missing"})
                   _ (testing "a run for an unknown applicant is refused"
                       (is (= 422 (:status refused))))
                   created (post "/v3.6/workflow_runs"
                                 {:workflow_id "wf-1"
                                  :applicant_id applicant-id
                                  :tags ["bank:bnk.1" "verification:idv.1"]
                                  :link {:completed_redirect_url
                                         "https://tenant/back"}})
                   run (http/res->edn created)
                   run-id (:id run)
                   _ (testing
                       "a run waits on the person behind a link to its page"
                       (is (= 201 (:status created)))
                       (is (= "awaiting_input" (:status run)))
                       (is (= (str *base-url* "/l/" run-id)
                              (get-in run [:link :url]))))
                   listed (get "/v3.6/workflow_runs?tags=verification:idv.1")
                   _ (testing "a run is found by its tags"
                       (is (= [run-id] (map :id (http/res->edn listed)))))
                   page (get (str "/l/" run-id))
                   _ (testing "the link opens the hosted page"
                       (is (= 200 (:status page))))
                   decided (post
                            (str "/simulator/workflow-runs/" run-id "/decision")
                            {:outcome "sanctions-hit"
                             :givenNames "Arthur"
                             :familyName "Dent"
                             :dateOfBirth "1952-03-11"})
                   _ (testing "a decision finishes the run as its outcome ends"
                       (is (= 200 (:status decided)))
                       (is (= "declined" (:status (http/res->edn decided)))))
                   checks (get (str "/v3.6/checks?applicant_id=" applicant-id))
                   check (first (:checks (http/res->edn checks)))
                   reports (get (str "/v3.6/reports?check_id=" (:id check)))
                   by-name (into {}
                                 (map (fn [r] [(:name r) r]))
                                 (:reports (http/res->edn reports)))
                   _ (testing
                       "the run's check holds the reports its outcome gives"
                       (is (= #{"document" "facial_similarity_motion"
                                "proof_of_address" "watchlist_aml"}
                              (set (keys by-name))))
                       (is (= "Arthur"
                              (get-in by-name
                                      ["document" :properties :first_name])))
                       (is (= "consider"
                              (get-in by-name
                                      ["watchlist_aml" :breakdown :sanction
                                       :result]))))
                   again (post
                          (str "/simulator/workflow-runs/" run-id "/decision")
                          {:outcome "match"})
                   _ (testing "a finished run takes no second decision"
                       (is (= 409 (:status again))))])))))

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
                     _ (is (= "test-token" (:token body)))
                     id (:id body)
                     res (get "/v3.6/webhooks")
                     _ (is (= 200 (:status res)))
                     list-body (http/res->edn res)
                     _ (is (some (fn [w] (= id (:id w))) (:webhooks list-body)))
                     res (delete (str "/v3.6/webhooks/" id))
                     _ (is (= 204 (:status res)))]))))))
