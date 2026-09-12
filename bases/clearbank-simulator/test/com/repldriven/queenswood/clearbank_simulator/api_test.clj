(ns com.repldriven.queenswood.clearbank-simulator.api-test
  (:refer-clojure :exclude [get])
  (:require
    [com.repldriven.queenswood.clearbank-simulator.system]

    [com.repldriven.queenswood.clearbank-simulator.api :as api]

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
  (http/request
   {:method :post
    :url (str *base-url* path)
    :headers {"Content-Type" "application/json"}
    :body (json/write-str body)}))

(defn- get
  [path]
  (http/request {:method :get
                 :url (str *base-url* path)}))

(defn- delete
  [path]
  (http/request {:method :delete
                 :url (str *base-url* path)}))

(defn- test-openapi-spec
  []
  (nom-test>
    [res (get "/openapi.json")
     _ (is (= 200 (:status res)))
     spec (http/res->edn res)
     _ (is (= "3.1.0" (:openapi spec)))]))

(defn- test-webhook-crud
  []
  (nom-test>
    [;; register a webhook
     res (post "/v1/webhooks"
               {:type "TransactionSettled"
                :url "http://localhost:9999/hook"})
     _ (is (= 201 (:status res)))
     body (http/res->edn res)
     _ (is (= "TransactionSettled" (:type body)))

     ;; list webhooks
     res (get "/v1/webhooks")
     _ (is (= 200 (:status res)))
     body (http/res->edn res)
     _ (is (= 1 (count (:webhooks body))))

     ;; reject unknown type
     res (post "/v1/webhooks"
               {:type "UnknownType"
                :url "http://localhost:9999/hook"})
     _ (is (= 400 (:status res)))

     ;; deregister
     res (delete "/v1/webhooks/TransactionSettled")
     _ (is (= 204 (:status res)))

     ;; list empty
     res (get "/v1/webhooks")
     _ (is (= 200 (:status res)))
     body (http/res->edn res)
     _ (is (empty? (:webhooks body)))

     ;; deregister non-existent
     res (delete "/v1/webhooks/TransactionSettled")
     _ (is (= 404 (:status res)))]))

(defn- test-fps-payment
  []
  (nom-test>
    [res (post "/v3/payments/fps"
               {:paymentInstructions
                [{:paymentInstructionIdentification "instr-001"
                  :paymentTypeCode "SIP"
                  :debtorAccount
                  {:identification
                   {:other
                    {:identification "12345678"
                     :schemeName
                     {:proprietary "SortCodeAccountNumber"}
                     :issuer "123456"}}}
                  :creditTransfers
                  [{:paymentIdentification
                    {:instructionIdentification "ct-001"
                     :endToEndIdentification "e2e-001"}
                    :amount {:instructedAmount 100.00
                             :currency "GBP"}
                    :creditor {:name "Arthur Dent"}
                    :creditorAccount
                    {:identification
                     {:other
                      {:identification "87654321"
                       :schemeName
                       {:proprietary "SortCodeAccountNumber"}
                       :issuer "654321"}}}}]}]})
     _ (is (= 202 (:status res)))
     body (http/res->edn res)
     _ (is (= 1 (count (:transactions body))))
     _ (is (= "e2e-001"
              (:endToEndIdentification
               (first (:transactions body)))))
     _ (is (= "Accepted"
              (:response (first (:transactions body)))))]))

(defn- test-simulate-inbound-payment
  []
  (nom-test>
    [res (post "/simulate/inbound-payment"
               {:bban "04000412345678"
                :amount 100.00
                :currency "GBP"
                :reference "Test inbound"})
     _ (is (= 202 (:status res)))
     body (http/res->edn res)
     _ (is (string? (:endToEndIdentification body)))]))

(defn- test-simulate-inbound-held
  "The sandbox sentinel debtor name holds the inbound, then auto-resolves
  per `outcome` (released by default, or returned)."
  []
  (nom-test>
    [released (post "/simulate/inbound-payment"
                    {:bban "04000412345678"
                     :amount 100.00
                     :currency "GBP"
                     :debtor-name "6a41a29eafcf455493"
                     :outcome "release"})
     _ (is (= 202 (:status released)))
     returned (post "/simulate/inbound-payment"
                    {:bban "04000412345678"
                     :amount 100.00
                     :currency "GBP"
                     :debtor-name "6a41a29eafcf455493"
                     :outcome "return"})
     _ (is (= 202 (:status returned)))]))

(deftest clearbank-simulator-test
  (with-test-system [sys
                     ["classpath:clearbank-simulator/application-test.yml"
                      #(assoc-in % [:system/defs :server :handler] api/app)]]
                    (let [jetty (system/instance sys [:server :jetty-adapter])]
                      (binding [*base-url* (server/http-local-url jetty)]
                        (testing "GET /openapi.json returns valid OpenAPI spec"
                          (test-openapi-spec))
                        (testing "Webhook CRUD" (test-webhook-crud))
                        (testing "POST /v3/payments/fps returns 202"
                          (test-fps-payment))
                        (testing "POST /simulate/inbound-payment returns 202"
                          (test-simulate-inbound-payment))
                        (testing "POST /simulate/inbound-payment held trigger"
                          (test-simulate-inbound-held))))))
