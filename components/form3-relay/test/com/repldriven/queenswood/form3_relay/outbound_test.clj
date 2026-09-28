(ns com.repldriven.queenswood.form3-relay.outbound-test
  "Drives the runner with a `post-fn` standing in for Form3, which the
  runner takes as configuration, so nothing is redefined for the JVM."
  (:require
    [com.repldriven.queenswood.form3-relay.test-system]

    [com.repldriven.queenswood.form3-relay.outbound :as SUT]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.form3-relay.interface :as relay]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing]]))

(defn- json-response
  [status body]
  {:status status
   :headers {:content-type "application/json"}
   :body (json/write-str body)})

(defn- recording
  "A `post-fn` answering each call with `(answer request)`, keeping every
  request it was given."
  [calls answer]
  (fn [_config request] (swap! calls conj request) (answer request)))

(defn- runner-config
  [sys post-fn]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])
   :schemas (system/instance sys [:avro :serde])
   :form3-url "http://form3.invalid"
   :sort-code "040075"
   :max-attempts 3
   :initial-backoff-ms 1000
   :max-backoff-ms 60000
   :reconcile-after-ms 60000
   :post-fn post-fn})

(defn- intent
  [intent-id kind dedup-key context & [extra]]
  (merge {:intent-id intent-id
          :dedup-key dedup-key
          :kind kind
          :request "{}"
          :status "pending"
          :attempts 0
          :created-at (utility/now)
          :context (pr-str context)}
         extra))

(defn- load-intent
  [config intent-id]
  (fdb/transact config
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn
                                                     "form3-outbound-intents")
                                           intent-id)
                          schema/pb->Form3OutboundIntent))))

(defn- outbox-event
  [config dedup-key]
  (fdb/transact config
                (fn [txn]
                  (some-> (fdb/query-record (fdb/open txn "form3-outbox")
                                            "Form3OutboxEvent"
                                            "dedup_key"
                                            dedup-key
                                            {:index
                                             "Form3OutboxEvent_by_dedup_key"})
                          schema/pb->Form3OutboxEvent))))

(defn- decoded
  [config event]
  (avro/deserialize-same (get (:schemas config) (:event-name event))
                         (:payload event)))

(deftest payment-created-then-submitted-test
  (with-test-system
   [sys "classpath:form3-relay/application-test.yml"]
   (let [calls (atom [])
         config (runner-config sys
                               (recording calls
                                          (fn [{:keys [path]}]
                                            (if (re-find #"submissions$" path)
                                              (json-response 409 {})
                                              (json-response 201 {})))))]
     (nom-test> [_ (relay/save-intent
                    config
                    (intent "int.p1"
                            "payment"
                            "pmt.1"
                            {:amount 150 :currency "GBP" :submission-id "S1"}
                            {:provider-payment-id "P1"
                             :request "{\"amount\":\"1.50\"}"}))])
     (SUT/drain-once config 0)
     (testing "the payment is created, then submitted, under the ids chosen"
       (is (= [["/v1/transaction/payments" "P1"]
               ["/v1/transaction/payments/P1/submissions" "S1"]]
              (mapv (fn [{:keys [path body]}] [path (get-in body [:data :id])])
                    @calls))))
     (testing "a submission Form3 already holds counts as made"
       (is (= "sent" (:status (load-intent config "int.p1"))))))))

(deftest payment-refused-test
  (with-test-system
   [sys "classpath:form3-relay/application-test.yml"]
   (let [config (runner-config
                 sys
                 (fn [_ _] (json-response 400 {:error_message "Bad sort"})))]
     (nom-test> [_ (relay/save-intent
                    config
                    (intent "int.p2"
                            "payment"
                            "pmt.2"
                            {:amount 150 :currency "GBP" :submission-id "S2"}
                            {:provider-payment-id "P2"}))])
     (SUT/drain-once config 0)
     (is (= "failed" (:status (load-intent config "int.p2"))))
     (let [data (decoded config
                         (outbox-event config "pmt.2:submission-rejected"))]
       (is (= :failure-kind-refused (:failure-kind data)))
       (is (= "Bad sort" (:cancellation-reason data)))))))

(deftest account-registered-under-an-issued-number-test
  (with-test-system
   [sys "classpath:form3-relay/application-test.yml"]
   (let [calls (atom [])
         config (runner-config
                 sys
                 (recording calls
                            (fn [{:keys [body]}]
                              (json-response
                               201
                               {:data (assoc (:data body)
                                             :attributes
                                             (assoc (get-in body
                                                            [:data :attributes])
                                                    :status
                                                    "confirmed"))}))))]
     (nom-test> [_ (relay/save-intent config
                                      (intent "int.o1" "open-account"
                                              "open:acc.1" {:bank-id "bnk.1"
                                                            :account-id "acc.1"
                                                            :holder-name
                                                            "Arthur Dent"
                                                            :currency "GBP"}))])
     (SUT/drain-once config 0)
     (let [{:keys [attributes]} (:data (:body (first @calls)))
           data (decoded config
                         (outbox-event config
                                       "open:acc.1:payment-account-opened"))]
       (testing "the number is issued under the configured sort code"
         (is (= "040075" (:bank_id attributes)))
         (is (re-matches #"\d{8}" (:account_number attributes)))
         (is (= ["Arthur Dent"] (:name attributes))))
       (testing "the opening reports the registration and its address"
         (is (= (get-in (first @calls) [:body :data :id])
                (:provider-account-id data)))
         (is (= [{:scheme "scan"
                  :sort-code "040075"
                  :account-number (:account_number attributes)}]
                (mapv (fn [a]
                        (select-keys a [:scheme :sort-code :account-number]))
                      (:addresses data)))))
       (is (= "settled" (:status (load-intent config "int.o1"))))
       (is (some? (:account-number (edn/read-string
                                    (:context (load-intent config
                                                           "int.o1"))))))))))

(deftest reconcile-reads-the-submission-back-test
  (with-test-system
   [sys "classpath:form3-relay/application-test.yml"]
   (let [config (runner-config sys
                               (fn [_ _]
                                 (json-response
                                  200
                                  {:data {:attributes {:status "delivery_failed"
                                                       :status_reason
                                                       "account_closed"}}})))]
     (nom-test> [_ (relay/save-intent
                    config
                    (intent "int.p3"
                            "payment"
                            "pmt.3"
                            {:amount 150 :currency "GBP" :submission-id "S3"}
                            {:provider-payment-id "P3"
                             :status "sent"
                             :next-attempt-at 0}))])
     (SUT/drain-once config 1)
     (is (= "settled" (:status (load-intent config "int.p3"))))
     (is (= "AC04"
            (:reason-code (decoded config
                                   (outbox-event config "P3:rejected"))))))))
