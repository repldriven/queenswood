(ns com.repldriven.queenswood.zyphe-simulator.api-test
  (:refer-clojure :exclude [get])
  (:require
    [com.repldriven.queenswood.zyphe-simulator.system]

    [com.repldriven.queenswood.zyphe-simulator.api :as api]

    [com.repldriven.queenswood.zyphe-webhook.interface :as zyphe-webhook]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]])
  (:import
    (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
    (java.net InetSocketAddress)))

(def ^:dynamic *base-url* "http://localhost:{PORT}")

(def ^:private flow-id "2d8285d7-f4ba-42df-ab3f-681d9870d37a")

(def ^:private secret
  "9f2b7c1d4e6a8035b1c9d2e4f6a80351d7e9b2c4f6a803517d9e2b4c6f8a0351")

(defn- post
  ([path body] (post path body {"x-api-key" "zyphe_sk_simulator"}))
  ([path body headers]
   (http/request {:method :post
                  :url (str *base-url* path)
                  :headers (merge {"Content-Type" "application/json"} headers)
                  :body (json/write-str body)})))

(defn- get
  [path]
  (http/request {:method :get :url (str *base-url* path)}))

(def ^:private create-path (str "/sdk/flow/" flow-id "/vr/create?sandbox=true"))

(defn- create-body
  ([party-id] (create-body party-id nil))
  ([party-id webhook-url]
   (cond-> {:credentials [{:type "EXTERNAL_ID" :externalId party-id}]
            :customData {:bankId "bnk.1" :verificationId "idv.1"}}
           webhook-url
           (assoc :webhook
                  {:url webhook-url :secret secret :payloadVersion "V2"}))))

(defn- receiver
  "A JDK HTTP server on a free port that hands each delivery's
  `X-Signature` and raw body to `deliveries`."
  [deliveries]
  (doto (HttpServer/create (InetSocketAddress. "localhost" 0) 0)
    (.createContext "/"
                    (reify
                     HttpHandler
                       (handle [_ exchange]
                         (let [^HttpExchange ex exchange
                               body (.readAllBytes (.getRequestBody ex))]
                           (deliver deliveries
                                    {:signature (.getFirst (.getRequestHeaders
                                                            ex)
                                                           "X-Signature")
                                     :body body})
                           (.sendResponseHeaders ex 200 -1)
                           (.close ex)))))
    (.start)))

(defmacro ^:private with-simulator
  [& body]
  `(with-test-system
    [sys#
     ["classpath:zyphe-simulator/application-test.yml"
      (fn [defs#] (assoc-in defs# [:system/defs :server :handler] api/app))]]
    (let [jetty# (system/instance sys# [:server :jetty-adapter])]
      (binding [*base-url* (server/http-local-url jetty#)] ~@body))))

(deftest openapi-test
  (with-simulator (nom-test> [res (get "/openapi.json")
                              _ (is (= 200 (:status res)))
                              spec (http/res->edn res)
                              _ (is (= "3.2.0" (:openapi spec)))])))

(deftest api-key-test
  (with-simulator (testing "a request with no x-api-key is refused"
                    (nom-test> [res (post create-path (create-body "pty.1") {})
                                _ (is (= 401 (:status res)))
                                body (http/res->edn res)
                                _ (is (= "missing_api_key" (:errorTag body)))]))
                  (testing "a request with the wrong key is refused"
                    (nom-test> [res (post create-path
                                          (create-body "pty.1")
                                          {"x-api-key" "zyphe_sk_wrong"})
                                _ (is (= 401 (:status res)))
                                body (http/res->edn res)
                                _ (is (= "invalid_api_key" (:errorTag body)))
                                _ (is (= 10201 (:code body)))]))))

(deftest create-and-resume-test
  (with-simulator
   (testing "a request naming no identity is refused"
     (nom-test> [res (post create-path {:customData {:bankId "bnk.1"}})
                 _ (is (= 400 (:status res)))]))
   (testing "creating returns a pending run keyed by the external id"
     (nom-test> [res (post create-path (create-body "pty.2"))
                 _ (is (= 200 (:status res)))
                 body (http/res->edn res)
                 vr (:verificationRequest body)
                 _ (is (= "PENDING" (:status vr)))
                 _ (is (= "EXTERNAL_ID:pty.2" (:zid body)))
                 _ (is (= {:bankId "bnk.1" :verificationId "idv.1"}
                          (:customData vr)))
                 again (post create-path (create-body "pty.2"))
                 again-body (http/res->edn again)
                 _ (is (= (:id vr)
                          (get-in again-body [:verificationRequest :id]))
                       "creating again for the same person resumes the run")]))))

(deftest decision-delivers-a-signed-event-test
  (let [deliveries (promise)
        server (receiver deliveries)]
    (try
      (with-simulator
       (nom-test> [res (post create-path
                             (create-body "pty.3"
                                          (str "http://localhost:"
                                               (.getPort (.getAddress server))
                                               "/webhooks/zyphe")))
                   body (http/res->edn res)
                   _ (is (= "…0351"
                            (get-in body [:sessionWebhook :secretHint])))
                   id (get-in body [:verificationRequest :id])
                   decided (post (str "/simulator/verification-requests/"
                                      id
                                      "/decision")
                                 {:flowStatus "REJECTED"})
                   _ (is (= 200 (:status decided)))
                   _ (is (= "REJECTED" (:status (http/res->edn decided))))
                   {:keys [signature] delivered :body}
                   (deref deliveries 5000 nil)
                   _ (is (some? delivered)
                         "the event reached the session webhook")
                   _ (is (not (error/anomaly? (zyphe-webhook/verify
                                               secret
                                               signature
                                               delivered
                                               (utility/now))))
                         "signed with the secret the request supplied")
                   event (json/read-str (String. ^bytes delivered "UTF-8"))
                   _ (is (= "verification.dv.failed"
                            (clojure.core/get event "type")))
                   _ (is (= {"status" "REJECTED"
                             "slug" "onboarding"
                             "customData" {"bankId" "bnk.1"
                                           "verificationId" "idv.1"}
                             "nextStep" nil}
                            (clojure.core/get event "flow")))]))
      (finally (.stop ^HttpServer server 0)))))
