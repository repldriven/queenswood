(ns com.repldriven.queenswood.form3-simulator.api-test
  (:require
    [com.repldriven.queenswood.form3-simulator.system]

    [com.repldriven.queenswood.form3-simulator.api :as api]

    [com.repldriven.queenswood.form3-webhook.interface :as form3-webhook]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
    (java.net InetSocketAddress URI)
    (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def ^:dynamic *base-url* nil)

(def ^:dynamic *credentials* nil)

(def ^:private sort-code "040075")

(defn- call
  ([method path] (call method path nil {}))
  ([method path body] (call method path body {}))
  ([method path body {:keys [signed?] :or {signed? true}}]
   (let [url (str *base-url* path)
         uri (URI. url)
         body (when body (json/write-str body))
         signature (when signed?
                     (form3-webhook/headers *credentials*
                                            {:method method
                                             :path (.getRawPath uri)
                                             :query (.getRawQuery uri)
                                             :host (str (.getHost uri)
                                                        ":"
                                                        (.getPort uri))
                                             :body body}
                                            (utility/now)))]
     (http/request (utility/assoc-some
                    {:method method
                     :url url
                     :headers (merge {"Content-Type"
                                      "application/vnd.api+json"}
                                     signature)}
                    :body
                    body)))))

(defn- control
  [path body]
  (http/request {:method :post
                 :url (str *base-url* path)
                 :headers {"Content-Type" "application/json"}
                 :body (json/write-str body)}))

(defn- edn [res] (http/res->edn res))

(defn- receiver
  "A JDK HTTP server on a free port that queues each delivery's parsed
  body."
  [queue]
  (doto (HttpServer/create (InetSocketAddress. "localhost" 0) 0)
    (.createContext "/"
                    (reify
                     HttpHandler
                       (handle [_ exchange]
                         (let [^HttpExchange ex exchange]
                           (.put ^LinkedBlockingQueue queue
                                 (json/read-str (slurp (.getRequestBody ex))
                                                :key-fn
                                                keyword))
                           (.sendResponseHeaders ex 200 -1)
                           (.close ex)))))
    (.start)))

(defn- next-delivery
  ([queue] (next-delivery queue 5000))
  ([queue ms] (.poll ^LinkedBlockingQueue queue ms TimeUnit/MILLISECONDS)))

(defn- subscribe
  [server record-types]
  (doseq [t record-types]
    (call :post
          "/v1/notification/subscriptions"
          {:data {:id (str (utility/uuidv7))
                  :type "subscriptions"
                  :attributes {:callback_transport "http"
                               :callback_uri
                               (str "http://localhost:"
                                    (.getPort (.getAddress ^HttpServer
                                                           server)))
                               :record_type t
                               :event_type "*"}}})))

(defmacro ^:private with-simulator
  [& body]
  `(with-test-system
    [sys#
     ["classpath:form3-simulator/application-test.yml"
      (fn [defs#] (assoc-in defs# [:system/defs :server :handler] api/app))]]
    (let [jetty# (system/instance sys# [:server :jetty-adapter])]
      (binding [*base-url* (server/http-local-url jetty#)
                *credentials* (system/instance sys# [:server :credentials])]
        ~@body))))

(defmacro ^:private with-receiver
  {:clj-kondo/lint-as 'clojure.core/fn}
  [[queue server] & body]
  `(let [~queue (LinkedBlockingQueue.)
         ~server (receiver ~queue)]
     (try ~@body (finally (.stop ~server 0)))))

(defn- account-number [] (format "%08d" (rand-int 100000000)))

(defn- register
  ([] (register (account-number)))
  ([number]
   (call :post
         "/v1/organisation/accounts"
         {:data {:id (str (utility/uuidv7))
                 :type "accounts"
                 :attributes {:bank_id sort-code
                              :bank_id_code "GBDSC"
                              :account_number number
                              :country "GB"
                              :base_currency "GBP"
                              :name ["Arthur Dent"]
                              :account_classification "personal"}}})))

(defn- pay
  ([beneficiary] (pay beneficiary "pmt.01K6A3Z9X0"))
  ([{:keys [bank-id account-number name]} e2e]
   (let [id (str (utility/uuidv7))]
     (call :post
           "/v1/transaction/payments"
           {:data {:id id
                   :type "payments"
                   :attributes {:amount "12.50"
                                :currency "GBP"
                                :payment_scheme "FPS"
                                :scheme_payment_type "ImmediatePayment"
                                :end_to_end_reference e2e
                                :reference "Towel"
                                :debtor_party {:account_number "00000001"
                                               :bank_id sort-code
                                               :bank_id_code "GBDSC"
                                               :account_name "The bank"}
                                :beneficiary_party {:account_number
                                                    account-number
                                                    :bank_id bank-id
                                                    :bank_id_code "GBDSC"
                                                    :account_name name}}}})
     id)))

(defn- submit
  [payment-id]
  (call :post
        (str "/v1/transaction/payments/" payment-id "/submissions")
        {:data {:id (str (utility/uuidv7)) :type "payment_submissions"}}))

(defn- deliveries-until
  "Deliveries, in order, up to the first `done?` holds of."
  [queue done?]
  (loop [out []]
    (let [d (next-delivery queue)]
      (cond
       (nil? d)
       out

       (done? d)
       (conj out d)

       :else
       (recur (conj out d))))))

(defn- status [d] (get-in d [:data :attributes :status]))

(defn- complete
  [task outcome & [reason]]
  (let [{:keys [id relationships]} (:data task)
        payment-id (get-in relationships [:payment :data 0 :id])
        admission-id (get-in relationships [:payment_admission :data 0 :id])]
    (call :patch
          (str "/v1/transaction/payments/" payment-id
               "/admissions/" admission-id
               "/tasks/" id)
          {:data {:id id
                  :type "payment_admission_tasks"
                  :version 0
                  :attributes {:status "completed"
                               :output (utility/assoc-some
                                        {:outcome outcome}
                                        :status_reason
                                        reason)}}})))

(defn- inbound
  [account-number]
  (future (edn (control "/simulate/inbound-payment"
                        {:bban (str sort-code account-number)
                         :amount 25
                         :currency "GBP"
                         :reference "Lunch"
                         :debtor-name "Ford Prefect"}))))

(deftest unsigned-call-is-refused-test
  (with-simulator
   (let [res (call :get "/v1/notification/subscriptions" nil {:signed? false})]
     (is (= 401 (:status res)))
     (is (string? (:error_message (edn res)))))))

(deftest register-account-test
  (with-simulator
   (let [number (account-number)
         res (register number)]
     (testing "an account under the organisation's sort code is confirmed"
       (is (= 201 (:status res)))
       (is (= "confirmed" (get-in (edn res) [:data :attributes :status]))))
     (testing "its number is registered once"
       (is (= 409 (:status (register number)))))
     (testing "a registration the control route refuses fails"
       (is (= 204 (:status (control "/simulate/open-refused" {}))))
       (is (= "failed" (get-in (edn (register)) [:data :attributes :status]))))
     (testing "an account closes"
       (let [{:keys [id]} (:data (edn res))]
         (is (= "closed"
                (get-in (edn (call :patch
                                   (str "/v1/organisation/accounts/" id)
                                   {:data {:id id
                                           :version 0
                                           :attributes {:status "closed"}}}))
                        [:data :attributes :status]))))))))

(deftest outbound-payment-test
  (with-receiver
   [queue server]
   (with-simulator
    (subscribe server ["payment_submissions"])
    (testing "a payment elsewhere is delivered"
      (let [id (pay {:bank-id "200000" :account-number "12345678" :name "Ford"})
            res (submit id)]
        (is (= 201 (:status res)))
        (is (= "accepted" (get-in (edn res) [:data :attributes :status])))
        (is (= "delivery_confirmed" (status (next-delivery queue))))
        (is (= "12.50"
               (get-in (edn (call :get (str "/v1/transaction/payments/" id)))
                       [:data :attributes :amount])))))
    (testing "the declined sort code fails delivery"
      (submit (pay {:bank-id "000000" :account-number "12345678" :name "F"}))
      (is (= "delivery_failed" (status (next-delivery queue)))))
    (testing "the refused sort code is refused at submission"
      (is (= 400
             (:status (submit (pay {:bank-id "999998"
                                    :account-number "12345678"
                                    :name "F"}))))))
    (testing "the held name is held on a limit check, then fails"
      (submit (pay {:bank-id "200000"
                    :account-number "12345678"
                    :name "6a41a29eafcf455493"}))
      (is (= ["limit_check_pending" "limit_check_failed"]
             [(status (next-delivery queue))
              (status (next-delivery queue))]))))))

(deftest inbound-admission-test
  (with-receiver
   [queue server]
   (with-simulator
    (subscribe server ["payment_admission_tasks" "payment_admissions"])
    (let [number (account-number)
          _ (register number)]
      (testing "the bank is given a task, and passing it admits the payment"
        (let [answer (inbound number)
              task (next-delivery queue)]
          (is (= "payment_admission_tasks" (:record_type task)))
          (is (= {:name "account_check" :assignee "customer" :status "pending"}
                 (select-keys (get-in task [:data :attributes])
                              [:name :assignee :status])))
          (is (= 200 (:status (complete task "passed"))))
          (is (= {:admission-status "confirmed" :status-reason "accepted"}
                 (select-keys @answer [:admission-status :status-reason])))
          (let [admission (next-delivery queue)]
            (is (= "payment_admissions" (:record_type admission)))
            (is (= "confirmed" (status admission))))
          (testing "and a task is completed once"
            (is (= 409 (:status (complete task "passed")))))))
      (testing "failing the task fails the admission with its reason"
        (let [answer (inbound number)
              task (next-delivery queue)]
          (complete task "failed" "transaction_forbidden")
          (is (= {:admission-status "failed"
                  :status-reason "transaction_forbidden"}
                 (select-keys @answer [:admission-status :status-reason])))
          (next-delivery queue)))
      (testing "a task not completed in time fails the admission"
        (let [answer (inbound number)]
          (next-delivery queue)
          (is (= "failed" (:admission-status @answer)))
          (is (= "beneficiary_agent_clearing_process_timeout"
                 (:status-reason @answer)))
          (next-delivery queue)))
      (testing "an address registered nowhere here is not simulated"
        (is (= 404
               (:status (control "/simulate/inbound-payment"
                                 {:bban (str sort-code "99999999")
                                  :amount 1
                                  :currency "GBP"})))))))))

(deftest inbound-to-a-closed-account-test
  (with-receiver
   [queue server]
   (with-simulator
    (subscribe server ["payment_admission_tasks" "payment_admissions"])
    (let [number (account-number)
          {:keys [id]} (:data (edn (register number)))]
      (call :patch
            (str "/v1/organisation/accounts/" id)
            {:data {:id id :version 0 :attributes {:status "closed"}}})
      (is (= {:admission-status "failed" :status-reason "account_closed"}
             (select-keys @(inbound number)
                          [:admission-status :status-reason])))
      (testing "without asking the bank"
        (is (= "payment_admissions" (:record_type (next-delivery queue)))))))))

(deftest payment-to-an-account-here-test
  (with-receiver
   [queue server]
   (with-simulator
    (subscribe server ["payment_admission_tasks" "payment_submissions"])
    (let [number (account-number)]
      (register number)
      (submit (pay {:bank-id sort-code :account-number number :name "Arthur"}))
      (let [task (next-delivery queue)]
        (is (= "payment_admission_tasks" (:record_type task)))
        (complete task "passed"))
      (is (= "delivery_confirmed"
             (status (last (deliveries-until queue
                                             (fn [d]
                                               (= "payment_submissions"
                                                  (:record_type d)))))))))
    (testing "and one to an account closed here fails delivery"
      (let [number (account-number)
            {:keys [id]} (:data (edn (register number)))]
        (call :patch
              (str "/v1/organisation/accounts/" id)
              {:data {:id id :version 0 :attributes {:status "closed"}}})
        (submit (pay {:bank-id sort-code :account-number number :name "A"}))
        (let [d (next-delivery queue)]
          (is (= "delivery_failed" (status d)))
          (is (= "account_closed"
                 (get-in d [:data :attributes :status_reason])))))))))

(deftest return-an-inbound-test
  (with-receiver
   [queue server]
   (with-simulator
    (subscribe server ["payment_admission_tasks" "return_submissions"])
    (let [number (account-number)
          _ (register number)
          answer (inbound number)
          _ (complete (next-delivery queue) "passed")
          payment-id (:endToEndIdentification @answer)
          return-id (str (utility/uuidv7))
          ret (fn []
                (call :post
                      (str "/v1/transaction/payments/" payment-id "/returns")
                      {:data {:id return-id
                              :type "returns"
                              :attributes {:amount "25.00"
                                           :currency "GBP"
                                           :return_code "AC04"}}}))]
      (testing "an admitted inbound is returned"
        (is (= 201 (:status (ret))))
        (is (= 201
               (:status (call :post
                              (str "/v1/transaction/payments/"
                                   payment-id
                                   "/returns/"
                                   return-id
                                   "/submissions")
                              {:data {:id (str (utility/uuidv7))
                                      :type "return_submissions"}}))))
        (let [d (next-delivery queue)]
          (is (= "return_submissions" (:record_type d)))
          (is (= "delivery_confirmed" (status d)))))
      (testing "once" (is (= 409 (:status (ret)))))))))

(deftest outbound-return-test
  (with-receiver
   [queue server]
   (with-simulator
    (subscribe server ["payment_submissions" "return_admissions"])
    (let [id (pay {:bank-id "200000" :account-number "12345678" :name "Ford"}
                  "pmt.01K6A3Z9X1")]
      (submit id)
      (next-delivery queue)
      (testing "a delivered payment is returned to the bank that sent it"
        (is (= 202
               (:status (control "/simulate/outbound-return"
                                 {:end-to-end-id "pmt.01K6A3Z9X1"
                                  :reason-code "AC01"}))))
        (let [d (next-delivery queue)
              {:keys [relationships]} (:data d)
              return-id (get-in relationships [:return :data 0 :id])]
          (is (= "return_admissions" (:record_type d)))
          (is (= id (get-in relationships [:payment :data 0 :id])))
          (is (= {:amount "12.50" :return_code "AC01"}
                 (select-keys (get-in (edn (call :get
                                                 (str
                                                  "/v1/transaction/payments/" id
                                                  "/returns/" return-id)))
                                      [:data :attributes])
                              [:amount :return_code])))))
      (testing "once"
        (is (= 409
               (:status (control "/simulate/outbound-return"
                                 {:end-to-end-id "pmt.01K6A3Z9X1"})))))
      (testing "and not one the simulator never saw"
        (is (= 404
               (:status (control "/simulate/outbound-return"
                                 {:end-to-end-id "pmt.unknown"})))))))))

(deftest name-verification-test
  (with-simulator
   (let [check (fn [name]
                 (-> (call :post
                           "/v1/organisation/nameverifications"
                           {:data {:id (str (utility/uuidv7))
                                   :type "name_verifications"
                                   :attributes {:account_number "12345678"
                                                :account_number_code "BBAN"
                                                :bank_id "200000"
                                                :bank_id_code "GBDSC"
                                                :name [name]
                                                :account_classification
                                                "personal"}}})
                     edn
                     (get-in [:data :relationships :name_verification_submission
                              :data 0 :attributes])
                     (select-keys [:answer :reason_code :actual_name])))]
     (is (= {:answer "confirmed"} (check "Ford Prefect")))
     (is (= {:answer "rejected" :reason_code "ANNM"} (check "COP_NOMATCH")))
     (is (= {:answer "rejected" :reason_code "MBAM" :actual_name "Ford"}
            (check "Ford COP_CLOSEMATCH")))
     (is (= {:answer "rejected" :reason_code "ACNS"}
            (check "COP_UNAVAILABLE"))))))

(deftest payments-by-end-to-end-reference-test
  (with-simulator
   (let [id (pay {:bank-id "200000" :account-number "12345678" :name "Ford"}
                 "pmt.01K6A3Z9X2")]
     (is (= [id]
            (mapv :id
                  (:data (edn (call :get
                                    (str "/v1/transaction/payments?"
                                         "filter%5Bend_to_end_reference%5D="
                                         "pmt.01K6A3Z9X2"))))))))))

(deftest openapi-test
  (with-simulator
   (let [doc (edn (http/request {:method :get
                                 :url (str *base-url* "/openapi.json")}))]
     (is (str/starts-with? (:openapi doc) "3"))
     (is (contains? (:paths doc)
                    (keyword "/v1/transaction/payments/{id}/submissions"))))))
