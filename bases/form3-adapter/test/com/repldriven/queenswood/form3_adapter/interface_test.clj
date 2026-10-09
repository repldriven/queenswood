(ns com.repldriven.queenswood.form3-adapter.interface-test
  "The adapter's notification route, with a JDK HTTP server standing in
  for Form3 where a notification is read back, and the platform's
  admission decision given to it rather than asked over the bus."
  (:require
    [com.repldriven.queenswood.form3-adapter.test-system]

    [com.repldriven.queenswood.form3-adapter.interface :as SUT]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
    (java.net InetSocketAddress)))

(def ^:dynamic *base-url* nil)

(def ^:dynamic *sys* nil)

(def ^:private task
  {:id "T1"
   :type "payment_admission_tasks"
   :version 0
   :attributes {:name "account_check" :assignee "customer" :status "pending"}
   :relationships {:payment {:data [{:type "payments" :id "P1"}]}
                   :payment_admission {:data [{:type "payment_admissions"
                                               :id "A1"}]}}})

(def ^:private payment
  {:id "P1"
   :type "payments"
   :attributes {:amount "25.00"
                :currency "GBP"
                :end_to_end_reference "E2E-1"
                :beneficiary_party {:bank_id "040075"
                                    :account_number "30000001"}
                :debtor_party {:account_name "Ford Prefect"}}})

(def ^:private resources
  {"/v1/transaction/payments/P1/admissions/A1/tasks/T1" task
   "/v1/transaction/payments/P1" payment
   "/v1/transaction/payments/P1/admissions/A1"
   {:id "A1" :attributes {:status "confirmed"}}})

(defn- fake-form3
  "A JDK HTTP server serving `resources` by path, 404 for anything else,
  and keeping the body of every PATCH."
  [patches]
  (doto (HttpServer/create (InetSocketAddress. "localhost" 0) 0)
    (.createContext
     "/"
     (reify
      HttpHandler
        (handle [_ exchange]
          (let [^HttpExchange ex exchange
                path (.getPath (.getRequestURI ex))
                method (.getRequestMethod ex)
                _ (when (= "PATCH" method)
                    (swap! patches conj
                      (json/read-str (slurp (.getRequestBody ex))
                                     :key-fn
                                     keyword)))
                found (get resources path)
                status (if (or found (= "PATCH" method)) 200 404)
                bytes (.getBytes ^String
                                 (json/write-str
                                  (if found
                                    {:data found}
                                    {:error_message "not found"}))
                                 "UTF-8")]
            (.add (.getResponseHeaders ex) "Content-Type" "application/json")
            (.sendResponseHeaders ex status (alength bytes))
            (with-open [out (.getResponseBody ex)] (.write out bytes))))))
    (.start)))

(defn- deciding
  "The platform's admission decision, as `decision`, keeping what it was
  asked."
  [asked decision]
  (reify
   clojure.lang.IFn
     (invoke [_ data] (swap! asked conj data) decision)))

(defmacro ^:private with-adapter
  {:clj-kondo/lint-as 'clojure.core/when}
  [[form3 decide] & body]
  `(let [f# ~form3]
     (try (with-test-system
           [sys#
            ["classpath:form3-adapter/application-test.yml"
             (fn [defs#]
               (-> defs#
                   (assoc-in [:system/defs :server :handler] SUT/app)
                   (assoc-in [:system/defs :server :interceptors :system/config
                              :form3-url]
                             (str "http://localhost:"
                                  (.getPort (.getAddress ^HttpServer f#))))
                   (assoc-in [:system/defs :server :interceptors :system/config
                              :admit-fn]
                             ~decide)))]]
           (binding [*sys* sys#
                     *base-url* (server/http-local-url
                                 (system/instance sys#
                                                  [:server :jetty-adapter]))]
             ~@body))
          (finally (.stop ^HttpServer f# 0)))))

(defn- notify
  [record-type event-type data]
  (http/request {:method :post
                 :url (str *base-url* "/webhooks/form3")
                 :headers {"Content-Type" "application/json"}
                 :body (json/write-str {:id "N1"
                                        :record_type record-type
                                        :event_type event-type
                                        :data data})}))

(defn- outbox-event
  [dedup-key]
  (fdb/transact {:record-db (system/instance *sys* [:fdb :record-db])
                 :record-store (system/instance *sys* [:fdb :store])}
                (fn [txn]
                  (some-> (fdb/query-record (fdb/open txn "form3-outbox")
                                            "Form3OutboxEvent"
                                            "dedup_key"
                                            dedup-key
                                            {:index
                                             "Form3OutboxEvent_by_dedup_key"})
                          schema/pb->Form3OutboxEvent))))

(deftest admission-task-passed-test
  (let [patches (atom [])
        asked (atom [])]
    (with-adapter
     [(fake-form3 patches)
      (deciding asked {:admitted true :payment-id "pmt.1"})]
     (let [res (notify "payment_admission_tasks" "created" task)]
       (is (= 200 (:status res)))
       (testing "the platform is asked with the inbound Form3 holds"
         (is (= [{:end-to-end-id "E2E-1"
                  :scheme "fps"
                  :creditor-bban "04007530000001"
                  :amount 2500
                  :currency "GBP"
                  :debtor-name "Ford Prefect"}]
                @asked)))
       (testing "and the task is completed as passed"
         (is (= {:status "completed" :output {:outcome "passed"}}
                (get-in (first @patches) [:data :attributes]))))))))

(deftest admission-task-failed-test
  (let [patches (atom [])]
    (with-adapter [(fake-form3 patches)
                   (deciding (atom []) {:admitted false :reason-code "AC04"})]
                  (notify "payment_admission_tasks" "created" task)
                  (is (= {:outcome "failed" :status_reason "account_closed"}
                         (get-in (first @patches)
                                 [:data :attributes :output]))))))

(deftest admission-confirmed-is-money-arriving-test
  (with-adapter [(fake-form3 (atom [])) (deciding (atom []) {:admitted true})]
                (is (= 200
                       (:status (notify "payment_admissions"
                                        "updated"
                                        {:id "A1"
                                         :relationships
                                         {:payment {:data [{:id "P1"}]}}}))))
                (let [event (outbox-event "P1:settled")]
                  (is (= "provider-payment-settled" (:event-name event)))
                  (is (= 2500
                         (:amount (avro/deserialize-same
                                   (get (system/instance *sys* [:avro :serde])
                                        "provider-payment-settled")
                                   (:payload event))))))))

(deftest a-resource-form3-does-not-hold-is-refused-test
  (with-adapter
   [(fake-form3 (atom [])) (deciding (atom []) {:admitted true})]
   (let [res (notify "payment_admissions"
                     "updated"
                     {:id "A9" :relationships {:payment {:data [{:id "P9"}]}}})]
     (is (= 400 (:status res)))
     (is (str/includes? (:body res) "unknown-resource")))))
