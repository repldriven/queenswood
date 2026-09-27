(ns com.repldriven.queenswood.modulr-simulator.api-test
  (:require
    [com.repldriven.queenswood.modulr-simulator.system]

    [com.repldriven.queenswood.modulr-simulator.api :as api]

    [com.repldriven.queenswood.modulr-webhook.interface :as modulr-webhook]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]])
  (:import
    (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
    (java.net InetSocketAddress)
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:dynamic *base-url* nil)

(def ^:private credentials
  {:key-id "adapter-key" :secret "adapter-secret" :algorithm "hmac-sha1"})

(def ^:private secret "0123456789abcdef0123456789abcdef")

(defn- call
  ([method path] (call method path nil {}))
  ([method path body] (call method path body {}))
  ([method path body {:keys [nonce retry? signed?] :or {signed? true}}]
   (let [nonce (or nonce (modulr-webhook/nonce))
         signature (when signed?
                     (modulr-webhook/headers credentials nonce (utility/now)))]
     (http/request (utility/assoc-some
                    {:method method
                     :url (str *base-url* path)
                     :headers (cond-> (merge {"Content-Type" "application/json"}
                                             signature)
                                      retry?
                                      (assoc "x-mod-retry" "true"))}
                    :body
                    (when body (json/write-str body)))))))

(defn- edn
  [res]
  (http/res->edn res))

(defn- receiver
  "A JDK HTTP server on a free port that queues each delivery's headers
  and parsed body."
  [queue]
  (doto (HttpServer/create (InetSocketAddress. "localhost" 0) 0)
    (.createContext "/"
                    (reify
                     HttpHandler
                       (handle [_ exchange]
                         (let [^HttpExchange ex exchange
                               headers (.getRequestHeaders ex)
                               body (slurp (.getRequestBody ex))]
                           (.put ^LinkedBlockingQueue queue
                                 {:headers {"authorization" (.getFirst
                                                             headers
                                                             "Authorization")
                                            "date" (.getFirst headers "Date")
                                            "x-mod-nonce" (.getFirst
                                                           headers
                                                           "x-mod-nonce")}
                                  :body (json/read-str body :key-fn keyword)})
                           (.sendResponseHeaders ex 200 -1)
                           (.close ex)))))
    (.start)))

(defn- next-delivery
  [queue]
  (.poll ^LinkedBlockingQueue queue 5 TimeUnit/SECONDS))

(defmacro ^:private with-simulator
  [& body]
  `(with-test-system
    [sys#
     ["classpath:modulr-simulator/application-test.yml"
      (fn [defs#] (assoc-in defs# [:system/defs :server :handler] api/app))]]
    (let [jetty# (system/instance sys# [:server :jetty-adapter])]
      (binding [*base-url* (server/http-local-url jetty#)] ~@body))))

(defn- open
  []
  (edn (call :post "/customers/C1/accounts" {:currency "GBP"})))

(defn- bban
  [account]
  (let [{:keys [sortCode accountNumber]} (first (:identifiers account))]
    (str sortCode accountNumber)))

(defn- balance
  [account-id]
  (:balance (edn (call :get (str "/accounts/" account-id)))))

(defn- subscribe
  [server types]
  (doseq [t types]
    (call :post
          "/customers/C1/integration-notifications"
          {:type t
           :url (str "http://localhost:"
                     (.getPort (.getAddress ^HttpServer
                                            server)))
           :retry true
           :secret secret
           :hmacAlgorithm "hmac-sha1"})))

(defn- pay
  [source destination amount]
  (call :post
        "/payments"
        {:sourceAccountId (:id source)
         :destination destination
         :amount amount
         :currency "GBP"
         :reference "Towel"
         :externalReference "pmt-01K6A3Z9X0"}))

(defn- scan
  [account name]
  (let [{:keys [sortCode accountNumber]} (first (:identifiers account))]
    {:type "SCAN" :sortCode sortCode :accountNumber accountNumber :name name}))

(deftest unsigned-call-is-refused-test
  (with-simulator
   (is (= 401
          (:status
           (call :post "/customers/C1/accounts" {} {:signed? false}))))))

(deftest open-account-test
  (with-simulator
   (testing
     "an account is opened with an address under the simulator's sort code"
     (let [account (open)]
       (is (= "ACTIVE" (:status account)))
       (is (= "0.00" (:balance account)))
       (is (re-matches #"040010\d{8}" (bban account)))))
   (testing "the next opening can be refused"
     (call :post "/simulate/open-refused")
     (is (= 400 (:status (call :post "/customers/C1/accounts" {}))))
     (is (= 201 (:status (call :post "/customers/C1/accounts" {})))))))

(deftest retry-is-the-same-request-test
  (with-simulator
   (let [account (open)
         nonce (modulr-webhook/nonce)
         body {:currency "GBP"}
         first-try (edn
                    (call :post "/customers/C1/accounts" body {:nonce nonce}))
         retried (edn (call :post
                            "/customers/C1/accounts"
                            body
                            {:nonce nonce :retry? true}))]
     (testing "a retry is answered with the first response"
       (is (= (:id first-try) (:id retried))))
     (testing "a reused nonce not marked a retry is refused"
       (is (= 400
              (:status
               (call :post "/customers/C1/accounts" body {:nonce nonce})))))
     (is (some? account)))))

(deftest payment-between-accounts-test
  (let [queue (LinkedBlockingQueue.)
        server (receiver queue)]
    (try (with-simulator
          (subscribe server ["PAYOUT" "PAYIN"])
          (let [a (open)
                b (open)]
            (call :post "/simulate/fund" {:bban (bban a) :amount 20})
            (testing "the payment is accepted before it is processed"
              (is (= "SUBMITTED" (:status (edn (pay a (scan b "Ford") 12.5))))))
            (let [payout (next-delivery queue)
                  payin (next-delivery queue)]
              (testing
                "the source is told, signed with the registration's secret"
                (is (= "PAYOUT" (get-in payout [:body :EventName])))
                (is (= "PROCESSED" (get-in payout [:body :Status])))
                (is (= "12.50" (get-in payout [:body :Amount])))
                (is (:verified (modulr-webhook/verify {:secret secret}
                                                      (:headers payout)
                                                      (utility/now)))))
              (testing "the destination is credited and told"
                (is (= "PAYIN" (get-in payin [:body :EventName])))
                (is (= (bban b)
                       (str (get-in payin [:body :Payee :Identifier :SortCode])
                            (get-in payin
                                    [:body :Payee :Identifier
                                     :AccountNumber]))))))
            (is (= "7.50" (balance (:id a))))
            (is (= "12.50" (balance (:id b))))))
         (finally (.stop server 0)))))

(deftest payment-waits-for-funds-test
  (let [queue (LinkedBlockingQueue.)
        server (receiver queue)]
    (try (with-simulator
          (subscribe server ["PAYOUT"])
          (let [a (open)
                b (open)
                p (edn (pay a (scan b "Ford") 5))]
            (Thread/sleep 200)
            (testing "a payment the balance cannot cover waits for funds"
              (is (= "PENDING_FOR_FUNDS"
                     (-> (call :get (str "/payments?id=" (:id p)))
                         edn
                         :content
                         first
                         :status))))
            (testing "money arriving releases it"
              (call :post "/simulate/fund" {:bban (bban a) :amount 5})
              (is (= "PROCESSED"
                     (get-in (next-delivery queue) [:body :Status]))))
            (testing "one never covered expires with no notification"
              (let [q (edn (pay a (scan b "Ford") 5))]
                (Thread/sleep 2500)
                (is (= "ER_EXPIRED"
                       (-> (call :get (str "/payments?id=" (:id q)))
                           edn
                           :content
                           first
                           :status)))
                (is (nil? (.poll queue 200 TimeUnit/MILLISECONDS)))))))
         (finally (.stop server 0)))))

(deftest test-values-test
  (let [queue (LinkedBlockingQueue.)
        server (receiver queue)]
    (try
      (with-simulator
       (subscribe server ["PAYOUT" "PAYMENT_COMPLIANCE_STATUS"])
       (let [a (open)]
         (call :post "/simulate/fund" {:bban (bban a) :amount 100})
         (testing "sort code 999998 is refused when submitted"
           (is (= 400
                  (:status (pay a
                                {:type "SCAN"
                                 :sortCode "999998"
                                 :accountNumber "00000001"
                                 :name "X"}
                                1)))))
         (testing "sort code 000000 is declined"
           (pay a
                {:type "SCAN" :sortCode "000000" :accountNumber "1" :name "X"}
                1)
           (is (= "ER_INVALID" (get-in (next-delivery queue) [:body :Status]))))
         (testing "the held name is held, then declined"
           (pay a
                {:type "SCAN"
                 :sortCode "203002"
                 :accountNumber "00004588"
                 :name "6a41a29eafcf455493"}
                1)
           (is (= ["HELD" "DECLINED" "CANCELLED"]
                  (mapv (fn [_]
                          (let [{:keys [body]} (next-delivery queue)]
                            (or (:ComplianceStatus body) (:Status body))))
                        (range 3)))))
         (is (= "100.00" (balance (:id a))) "declines move no money")))
      (finally (.stop server 0)))))

(deftest inbound-payment-test
  (let [queue (LinkedBlockingQueue.)
        server (receiver queue)]
    (try
      (with-simulator
       (subscribe server ["PAYIN" "PAYMENT_COMPLIANCE_STATUS"])
       (let [a (open)]
         (testing "a payment arrives"
           (call :post
                 "/simulate/inbound-payment"
                 {:bban (bban a) :amount 3.21 :currency "GBP" :reference "R"})
           (let [{:keys [body]} (next-delivery queue)]
             (is (= "PI_FAST" (:Type body)))
             (is (= "3.21" (:Amount body)))))
         (testing "a held one is released"
           (call :post
                 "/simulate/inbound-payment"
                 {:bban (bban a)
                  :amount 1
                  :currency "GBP"
                  :debtor-name "6a41a29eafcf455493"})
           (is (= ["HELD" "RELEASED" "PAYIN"]
                  (mapv (fn [_]
                          (let [{:keys [body]} (next-delivery queue)]
                            (or (:ComplianceStatus body) (:EventName body))))
                        (range 3)))))
         (testing "a held one is returned"
           (call :post
                 "/simulate/inbound-payment"
                 {:bban (bban a)
                  :amount 1
                  :currency "GBP"
                  :debtor-name "6a41a29eafcf455493"
                  :outcome "return"})
           (is (= ["HELD" "RETURNED"]
                  (mapv (fn [_]
                          (get-in (next-delivery queue)
                                  [:body :ComplianceStatus]))
                        (range 2)))))
         (is (= "4.21" (balance (:id a))))
         (testing "an address the simulator does not hold is not found"
           (is (= 404
                  (:status (call :post
                                 "/simulate/inbound-payment"
                                 {:bban "04000400000001"
                                  :amount 1
                                  :currency "GBP"})))))))
      (finally (.stop server 0)))))

(deftest name-check-test
  (with-simulator
   (let [a (open)
         check (fn [name]
                 (:result (edn (call :post
                                     "/account-name-check"
                                     {:paymentAccountId (:id a)
                                      :sortCode "203002"
                                      :accountNumber "00004588"
                                      :accountType "PERSONAL"
                                      :name name}))))]
     (is (= {:code "MATCHED"} (check "Ford Prefect")))
     (is (= {:code "NOT_MATCHED"} (check "COP_NOMATCH")))
     (is (= {:code "CLOSE_MATCH" :name "Ford"} (check "Ford COP_CLOSEMATCH")))
     (is (= {:code "ACCOUNT_NOT_SUPPORTED"} (check "COP_UNAVAILABLE"))))))

(deftest close-and-reissue-steps-test
  (with-simulator
   (let [a (open)
         b (open)]
     (call :post "/simulate/fund" {:bban (bban a) :amount 2})
     (testing "an account holding money is not closed"
       (is (= 400 (:status (call :post (str "/accounts/" (:id a) "/close"))))))
     (testing "a transfer between accounts moves the balance"
       (is (= 201 (:status (pay a {:type "ACCOUNT" :id (:id b)} 2))))
       (Thread/sleep 300)
       (is (= "0.00" (balance (:id a))))
       (is (= "2.00" (balance (:id b)))))
     (testing "an empty account is closed"
       (call :post (str "/accounts/" (:id a) "/block"))
       (is (= 204 (:status (call :post (str "/accounts/" (:id a) "/close")))))
       (is (= "CLOSED"
              (:status (edn (call :get (str "/accounts/" (:id a)))))))))))

(deftest balances-test
  (with-simulator
   (let [a (open)]
     (call :post "/simulate/fund" {:bban (bban a) :amount 9})
     (is (= "9.00"
            (some (fn [x] (when (= (:id a) (:id x)) (:balance x)))
                  (:accounts (edn (http/request
                                   {:method :get
                                    :url (str *base-url*
                                              "/simulate/balances")})))))))))

(deftest openapi-test
  (with-simulator
   (let [res (http/request {:method :get
                            :url (str *base-url* "/openapi.json")})]
     (is (= 200 (:status res)))
     (is (= "Modulr Simulator" (get-in (http/res->edn res) [:info :title]))))))
