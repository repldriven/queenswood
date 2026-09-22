(ns com.repldriven.queenswood.demo-digital-bank-api.api-test
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.system]

    [com.repldriven.queenswood.demo-digital-bank-api.api :as SUT]

    [com.repldriven.queenswood.demo-digital-bank.interface :as bank]
    ;; The platform stand-in is the core brick's test tree, and seeding an
    ;; opened account with money on it there is how a route test gets a
    ;; customer who can pay without booting the platform.

    ;; enforce-idioms: brick-test-scope -- stand-in from the core brick
    [com.repldriven.queenswood.demo-digital-bank.platform-stub :as stub]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]
    [com.repldriven.mono.utility.interface :as util]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.io BufferedReader InputStreamReader)
    (java.net URI)
    (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
    (java.util Base64)
    (javax.crypto Mac)
    (javax.crypto.spec SecretKeySpec)))

(def ^:private config "classpath:demo-digital-bank-api/application-test.yml")

(def ^:private details
  {:given-name "Amara"
   :family-name "Okafor"
   :date-of-birth "1994-03-12"
   :address {:building-number "12"
             :street "Mare Street"
             :town "London"
             :postcode "E8 3RH"}
   :national-identifier {:value "QQ123456C"}})

(defn- call
  [base-url method path {:keys [body token key]}]
  (let [res (http/request (cond-> {:method method
                                   :url (str base-url path)
                                   :headers (cond-> {"content-type"
                                                     "application/json"}
                                                    token
                                                    (assoc "authorization"
                                                           (str "Bearer "
                                                                token))

                                                    key
                                                    (assoc "idempotency-key"
                                                           key))}

                                  body
                                  (assoc :body (json/write-str body))))]
    {:status (:status res) :body (http/res->edn res)}))

(defn- sign-up
  "Walk the sign-up routes through to a session, answering its token."
  [base-url phone passcode]
  (let [started (call base-url :post "/sign-up" {:body {:phone phone}})
        id (get-in started [:body :id])
        coded (call base-url
                    :post
                    (str "/sign-up/" id "/code")
                    {:body {:code "123456"}})
        registered (call base-url
                         :post
                         (str "/sign-up/" id "/details")
                         {:body details})
        session (call base-url
                      :post
                      (str "/sign-up/" id "/passcode")
                      {:body {:passcode passcode}})]
    (is (= 201 (:status started)) (pr-str started))
    (is (= 200 (:status coded)) (pr-str coded))
    (is (= 200 (:status registered)) (pr-str registered))
    (is (= "pending" (get-in registered [:body :verification])))
    (is (= 201 (:status session)) (pr-str session))
    (get-in session [:body :token])))

(defn- with-bank
  [f]
  (with-test-system
   [sys [config #(assoc-in % [:system/defs :server :handler] SUT/app)]]
   (f (server/http-local-url (system/instance sys [:server :jetty-adapter]))
      sys)))

(defn- funded-everyday
  "An opened Everyday account with `balance` on it, seeded into the
  stand-in and recorded as the session's customer's, answering its id."
  [sys token balance]
  (let [bank (system/instance sys [:demo-digital-bank :bank])
        state (system/instance sys [:platform-stub :state])
        customer (bank/authenticate bank token)
        account-id (util/generate-id "acc")]
    (stub/seed-account state
                       {:account-id account-id
                        :party-id (:party-id customer)
                        :name "Everyday"
                        :currency "GBP"
                        :product-id "prd.00000000000000000000000001"
                        :product-type "current"
                        :account-status "opened"
                        :payment-addresses [{:scheme "scan"
                                             :scan {:sort-code "040075"
                                                    :account-number
                                                    "31908240"}}]
                        :posted-balance {:value balance :currency "GBP"}
                        :available-balance {:value balance :currency "GBP"}}
                       [])
    (bank/record-account bank
                         customer
                         {:account-id account-id
                          :product-kind "cur"
                          :name "Everyday"})
    account-id))

(deftest sign-up-and-home-test
  (with-bank
   (fn [base-url _]
     (let [token (sign-up base-url "07700 900123" "2468")]
       (testing "the session reads the home"
         (let [me (call base-url :get "/me" {:token token})]
           (is (= 200 (:status me)) (pr-str me))
           (is (= {:first "Amara"
                   :last "Okafor"
                   :phone "+447700900123"
                   :verification "verified"}
                  (dissoc (get-in me [:body :user]) :member-since)))
           (is (= [] (get-in me [:body :accounts])))
           (is (= ["Everyday" "Rainy Day" "1 Year Fixed"]
                  (map :name (get-in me [:body :products]))))))
       (testing "no session is 401, in problem details"
         (let [refused (call base-url :get "/me" {})]
           (is (= 401 (:status refused)))
           (is (= "UNAUTHORIZED" (get-in refused [:body :title])))))
       (testing "signing out ends the session"
         (is (= 204 (:status (call base-url :post "/sign-out" {:token token}))))
         (is (= 401 (:status (call base-url :get "/me" {:token token})))))))))

(deftest sign-in-and-refusals-test
  (with-bank
   (fn [base-url _]
     (sign-up base-url "07700 900200" "1357")
     (testing "a returning customer signs in"
       (let [session (call base-url
                           :post
                           "/sign-in"
                           {:body {:phone "07700 900200" :passcode "1357"}})]
         (is (= 201 (:status session)) (pr-str session))
         (is (= 200
                (:status (call base-url
                               :get
                               "/me"
                               {:token (get-in session [:body :token])}))))))
     (testing "a wrong passcode is 401"
       (is (= 401
              (:status (call base-url
                             :post
                             "/sign-in"
                             {:body {:phone "07700 900200"
                                     :passcode "0000"}})))))
     (testing "a malformed body is 400"
       (is (= 400
              (:status (call base-url
                             :post
                             "/sign-in"
                             {:body {:phone "07700 900200" :passcode "12"}})))))
     (testing "a step out of order is 409 naming the step expected"
       (let [started
             (call base-url :post "/sign-up" {:body {:phone "07700 900201"}})
             refused (call
                      base-url
                      :post
                      (str "/sign-up/" (get-in started [:body :id]) "/passcode")
                      {:body {:passcode "1111"}})]
         (is (= 409 (:status refused)))
         (is (= ":sign-up/invalid-status" (get-in refused [:body :type])))))
     (testing "an unknown sign-up is 404"
       (is (= 404
              (:status (call base-url
                             :post
                             "/sign-up/nope/code"
                             {:body {:code "123456"}}))))))))

(deftest payments-test
  (with-bank
   (fn [base-url sys]
     (let [token (sign-up base-url "07700 900300" "3579")
           everyday (funded-everyday sys token 100000)
           payee {:name "Arthur Dent"
                  :sort-code "04-00-62"
                  :account-number "12345678"}
           key (str (util/uuidv7))]
       (testing "a payee check answers the outcome"
         (let [checked
               (call base-url :post "/payee-checks" {:token token :body payee})]
           (is (= 200 (:status checked)) (pr-str checked))
           (is (= "match" (get-in checked [:body :outcome])))))
       (testing "a payment under a key is made once"
         (let [payment
               {:from everyday :payee payee :amount 2500 :reference "Towel"}
               sent (call base-url
                          :post
                          "/payments"
                          {:token token :key key :body payment})
               again (call base-url
                           :post
                           "/payments"
                           {:token token :key key :body payment})
               me (call base-url :get "/me" {:token token})]
           (is (= 201 (:status sent)) (pr-str sent))
           (is (= "pending" (get-in sent [:body :status])))
           (is (= "Arthur Dent" (get-in sent [:body :payee :name])))
           (is (= (get-in sent [:body :id]) (get-in again [:body :id])))
           (is (= 97500 (get-in me [:body :accounts 0 :balance])))
           (is (= ["Arthur Dent"] (map :name (get-in me [:body :payees]))))
           (is (= "Arthur Dent" (get-in me [:body :txns 0 :who])))
           (is (= "pending" (get-in me [:body :txns 0 :status])))))
       (testing "an account opens with its deposit, and money moves back"
         (let [opened (call base-url
                            :post
                            "/accounts"
                            {:token token
                             :key (str (util/uuidv7))
                             :body {:product-id "prd.00000000000000000000000002"
                                    :deposit 20000}})
               rainy (get-in opened [:body :account :id])
               moved (call base-url
                           :post
                           "/transfers"
                           {:token token
                            :key (str (util/uuidv7))
                            :body {:from rainy :to everyday :amount 5000}})
               me (call base-url :get "/me" {:token token})]
           (is (= 201 (:status opened)) (pr-str opened))
           (is (= "Rainy Day" (get-in opened [:body :account :name])))
           (is (= 20000 (get-in opened [:body :deposit :amount])))
           (is (= 201 (:status moved)) (pr-str moved))
           (is (= [82500 15000] (map :balance (get-in me [:body :accounts]))))))
       (testing
         "the bank's rules refuse as 422, and a stranger's account is 404"
         (let [same (call base-url
                          :post
                          "/transfers"
                          {:token token
                           :body {:from everyday :to everyday :amount 1}})
               small (call base-url
                           :post
                           "/accounts"
                           {:token token
                            :body {:product-id "prd.00000000000000000000000003"
                                   :deposit 1}})
               other (sign-up base-url "07700 900301" "8642")
               refused (call base-url
                             :post
                             "/payments"
                             {:token other
                              :body {:from everyday :payee payee :amount 1}})
               malformed (call base-url
                               :post
                               "/payments"
                               {:token token
                                :body {:from everyday :payee payee :amount 0}})]
           (is (= 422 (:status same)))
           (is (= ":transfer/same-account" (get-in same [:body :type])))
           (is (= 422 (:status small)))
           (is (= 404 (:status refused)))
           (is (= 400 (:status malformed)))))))))

(deftest openapi-test
  (with-bank
   (fn [base-url _]
     (let [res (http/request {:method :get :url (str base-url "/openapi.json")})
           spec (http/res->edn res)]
       (is (= 200 (:status res)))
       (is (contains? (:paths spec) (keyword "/sign-up/{sign-up-id}/details")))
       (is (contains? (:paths spec) (keyword "/me")))
       (is (contains? (:paths spec) (keyword "/payments")))
       (testing "the stream and the receiver name shapes the document holds"
         (is (= "#/components/schemas/Notification"
                (get-in
                 spec
                 [:paths (keyword "/events") :get :responses (keyword "200")
                  :content (keyword "text/event-stream") :schema :$ref])))
         (is (contains? (get-in spec [:components :schemas]) :Notification))
         (is (contains? (get-in spec [:components :schemas]) :Received)))))))

(def ^:private test-secret "whsec_ZGVtby1kaWdpdGFsLWJhbmstdGVzdC1zZWNyZXQtMzJi")

(defn- sign
  "The Standard Webhooks signature the platform would send: HMAC-SHA256
  over `id.timestamp.body` under the secret's base64 key bytes."
  [secret message-id timestamp ^bytes body]
  (let [key (.decode (Base64/getUrlDecoder) (subs secret 6))
        mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. key "HmacSHA256")))
        prefix (.getBytes (str message-id "." timestamp ".") "UTF-8")]
    (.update mac prefix)
    (str "v1," (.encodeToString (Base64/getEncoder) (.doFinal mac body)))))

(defn- post-delivery
  "POST a delivery as the platform would, signed under `secret` at
  `timestamp`, answering `{:status :body}`. No headers at all when
  `unsigned`."
  [base-url message-id envelope & {:keys [secret timestamp unsigned]}]
  (let [encoded (json/write-str envelope)
        body (.getBytes ^String encoded "UTF-8")
        timestamp (or timestamp (quot (util/now) 1000))
        res (http/request
             {:method :post
              :url (str base-url "/webhooks")
              :headers
              (cond->
               {"content-type" "application/json"}
               (not unsigned)
               (assoc "webhook-id" message-id
                      "webhook-timestamp" (str timestamp)
                      "webhook-signature"
                      (sign
                       (or secret test-secret)
                       message-id
                       timestamp
                       body)))
              :body encoded})]
    {:status (:status res) :body (http/res->edn res)}))

(defn- await-stream-release
  "Wait until the bank holds no stream open for the session's customer,
  answering how many it still holds. Closing the app's end reaches the
  bank only when the stream's next write fails, a keep-alive later; a
  delivery in that window is pushed to the stream nobody reads, written
  into a closed socket, and marked shown, and the next stream then has
  nothing to replay."
  [sys token timeout-ms]
  (let [bank (system/instance sys [:demo-digital-bank :bank])
        customer (bank/authenticate bank token)
        deadline (+ (util/now) timeout-ms)]
    (loop []
      (let [open (bank/open-streams bank customer)]
        (if (or (zero? open) (> (util/now) deadline))
          open
          (do (Thread/sleep 20) (recur)))))))

(defn- open-stream
  "GET the event stream under `token`, answering once its headers have
  arrived: `{:status :reader :close}`."
  [base-url token]
  (let [request (-> (HttpRequest/newBuilder (URI/create (str base-url
                                                             "/events")))
                    (.header "authorization" (str "Bearer " token))
                    (.header "accept" "text/event-stream")
                    .GET
                    .build)
        response (.send (HttpClient/newHttpClient)
                        request
                        (HttpResponse$BodyHandlers/ofInputStream))
        reader (BufferedReader. (InputStreamReader. (.body response) "UTF-8"))]
    {:status (.statusCode response)
     :reader reader
     :close (fn [] (.close reader))}))

(defn- next-event
  "The data of the next event on the stream, or nil once `timeout-ms`
  has passed; a keep-alive comment is skipped. Read on a daemon thread,
  so a read left blocked when the timeout passes cannot hold the JVM
  open."
  [{:keys [^BufferedReader reader]} timeout-ms]
  (let [result (promise)
        read (fn []
               (loop [lines []]
                 (let [line (.readLine reader)]
                   (cond (nil? line)
                         nil

                         (= "" line)
                         (if-let [data (some (fn [l]
                                               (when (str/starts-with? l
                                                                       "data:")
                                                 (subs l 5)))
                                             lines)]
                           (json/read-str (str/trim data) :key-fn keyword)
                           (recur []))

                         :else
                         (recur (conj lines line))))))]
    (doto (Thread. (fn []
                     (deliver result
                              (try (read) (catch java.io.IOException _ nil)))))
      (.setDaemon true)
      (.start))
    (deref result timeout-ms nil)))

(deftest notifications-test
  (with-bank
   (fn [base-url sys]
     (let [token (sign-up base-url "07700 900400" "4680")
           everyday (funded-everyday sys token 5000)
           state (system/instance sys [:platform-stub :state])
           envelope {:notification-id "whn.00000000000000000000000001"
                     :kind "cash-account.opened"
                     :change-kind "open"
                     :occurred-at "2026-09-19T10:00:00Z"
                     :bank-id "bnk.00000000000000000000000001"
                     :resource-type "CashAccount"
                     :resource-id everyday
                     :status-before "opening"
                     :status-after "opened"
                     :correlation-id "01998b6e-0e2e-7c3a-9a1e-5f6d2c4b8a02"
                     :data (get-in @state [:accounts everyday])}
           stream (open-stream base-url token)]
       (testing "the stream opens under the session, and not without one"
         (is (= 200 (:status stream)))
         (is (= 401 (:status (call base-url :get "/events" {})))))
       (testing "a signed delivery is taken, and reaches the stream"
         (let [taken (post-delivery base-url
                                    "whd.00000000000000000000000001"
                                    envelope)
               event (next-event stream 10000)]
           (is (= 202 (:status taken)) (pr-str taken))
           (is (= {:notification-id "whn.00000000000000000000000001"
                   :status "accepted"}
                  (:body taken)))
           (is (= "cash-account.opened" (:kind event)) (pr-str event))
           (is (= "Everyday is open" (:headline event)))
           (is (= everyday (:account event)))))
       (testing "money landing on the account reaches the stream as arriving"
         (let [arrived {:notification-id "whn.00000000000000000000000003"
                        :kind "payment.internal-settled"
                        :change-kind "settle"
                        :occurred-at "2026-09-21T10:00:00Z"
                        :bank-id "bnk.00000000000000000000000001"
                        :resource-type "InternalPayment"
                        :resource-id "pmt.00000000000000000000000001"
                        :status-after "settled"
                        :correlation-id "01998b6e-0e2e-7c3a-9a1e-5f6d2c4b8a03"
                        :data {:payment-id "pmt.00000000000000000000000001"
                               :bank-id "bnk.00000000000000000000000001"
                               :debtor-account-id "acc.house"
                               :creditor-account-id everyday
                               :currency "GBP"
                               :amount 5000
                               :transaction-id "txn.00000000000000000000000001"
                               :reference "Welcome"
                               :business-day "2026-09-21"
                               :created-at "2026-09-21T10:00:00Z"}}
               taken
               (post-delivery base-url "whd.00000000000000000000000007" arrived)
               event (next-event stream 10000)]
           (is (= 202 (:status taken)) (pr-str taken))
           (is (= "£50.00 arrived" (:headline event)) (pr-str event))
           (is (= everyday (:account event)))))
       (testing "a re-send of the same notification is done"
         (is (= "done"
                (get-in (post-delivery base-url
                                       "whd.00000000000000000000000002"
                                       envelope)
                        [:body :status]))))
       (testing
         "a delivery under another secret, a stale one, and an unsigned one are 401"
         (is (= 401
                (:status
                 (post-delivery
                  base-url
                  "whd.00000000000000000000000003" envelope
                  :secret
                  "whsec_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))))
         (is (= 401
                (:status
                 (post-delivery base-url
                                "whd.00000000000000000000000004" envelope
                                :timestamp (- (quot (util/now) 1000) 3600)))))
         (let [refused (post-delivery base-url "whd.5" envelope :unsigned true)]
           (is (= 401 (:status refused)))
           (is (= ":webhook/unsigned" (get-in refused [:body :type])))))
       ((:close stream))
       (testing "what was told while no stream was open arrives on the next"
         (is (zero? (await-stream-release sys token 5000)))
         (let [later (assoc envelope
                            :notification-id
                            "whn.00000000000000000000000002")
               _ (post-delivery base-url "whd.00000000000000000000000006" later)
               stream (open-stream base-url token)
               event (next-event stream 10000)]
           (is (= "whn.00000000000000000000000002" (:id event)) (pr-str event))
           ((:close stream))))))))

(deftest preflight-test
  (with-bank
   (fn [base-url _]
     (testing "the app's origin may send a submission with its key"
       (let [res (http/request
                  {:method :options
                   :url (str base-url "/accounts")
                   :headers {"origin" "http://localhost:5174"
                             "access-control-request-method" "POST"
                             "access-control-request-headers"
                             "authorization, content-type, idempotency-key"}})
             allowed (str/lower-case
                      (get-in res [:headers :access-control-allow-headers] ""))]
         (is (= 204 (:status res)) (pr-str res))
         (is (= "http://localhost:5174"
                (get-in res [:headers :access-control-allow-origin])))
         (is (str/includes? allowed "idempotency-key") allowed))))))
