(ns com.repldriven.queenswood.clearbank-relay.interface-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.clearbank-relay.store :as store]
    [com.repldriven.queenswood.clearbank-relay.interface :as SUT]
    [com.repldriven.queenswood.clearbank-relay.outbound.core :as outbound]
    [com.repldriven.queenswood.clearbank-webhook.interface :as
     clearbank-webhook]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(defn- event
  [outbox-id dedup-key]
  {:outbox-id outbox-id
   :dedup-key dedup-key
   :event-name "transaction-settled"
   :payload (.getBytes "avro-payload-bytes")
   :correlation-id "corr-1"
   :causation-id "caus-1"
   :created-at (utility/now)})

(deftest outbox-dedup-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}]
     (testing "a duplicate dedup-key is rejected by the unique index"
       (nom-test> [_ (SUT/save-event config (event "obx.1" "e2e-1:settled"))])
       (let [dup (SUT/save-event config (event "obx.2" "e2e-1:settled"))]
         (is (SUT/uniqueness-violation? dup)
             "a second save reusing the dedup-key must violate"))))))

(defn- intent-of
  [intent-id dedup-key]
  {:intent-id intent-id
   :idempotency-key dedup-key
   :kind :clearbank-outbound-intent-kind-payment
   :request "{\"paymentInstructions\":[]}"
   :status :outbound-intent-status-pending
   :attempt-count 0
   :created-at (utility/now)})

(deftest outbound-intent-queue-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])}]
     (testing "a duplicate dedup-key (redelivered submit) is rejected"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.1" "e2e-A"))])
       (is (SUT/uniqueness-violation?
            (SUT/save-intent config (intent-of "int.2" "e2e-A")))))
     (testing "a sent intent leaves the pending work-queue"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.3" "e2e-B"))])
       (is (some #(= "int.3" (:intent-id %))
                 (store/intents-with-status config
                                            :outbound-intent-status-pending)))
       (nom-test> [_ (store/mark-sent config "int.3")])
       (is (not (some #(= "int.3" (:intent-id %))
                      (store/intents-with-status
                       config
                       :outbound-intent-status-pending)))))
     (testing "a failed POST keeps the intent pending and bumps its attempt"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.4" "e2e-C"))])
       (outbound/drain-once (assoc config
                                   :clearbank-url "http://localhost:1"
                                   :signing-key (clearbank-webhook/key-pair)
                                   :delivery-policy
                                   {:default {:initial-backoff-ms 1000
                                              :backoff-growth 2
                                              :max-backoff-ms 60000
                                              :max-attempts 10
                                              :max-age-ms 86400000}
                                    :breaker {:failure-threshold 1000
                                              :cool-down-ms 1000
                                              :max-cool-down-ms 1000
                                              :probe-lease-ms 1000}})
                            (utility/now))
       (let [i4 (first (filter #(= "int.4" (:intent-id %))
                               (store/intents-with-status
                                config
                                :outbound-intent-status-pending)))]
         (is (some? i4) "still pending after an unreachable POST")
         (is (= 1 (:attempt-count i4)) "attempt count bumped"))))))

(defn- relay-config
  [sys post-fn]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])
   :schemas (system/instance sys [:avro :serde])
   :clearbank-url "http://scheme.invalid"
   :delivery-policy {:default {:initial-backoff-ms 1000
                               :backoff-growth 2
                               :max-backoff-ms 60000
                               :max-attempts 3
                               :max-age-ms 86400000}
                     :breaker {:failure-threshold 1000
                               :cool-down-ms 1000
                               :max-cool-down-ms 1000
                               :probe-lease-ms 1000}}
   :post-fn post-fn})

(defn- answering
  [calls res]
  (fn [_url _signing-key _request] (swap! calls inc) res))

(defn- fps-response
  [status end-to-end-id response]
  {:status status
   :headers {:content-type "application/json"}
   :body (str "{\"transactions\":[{\"endToEndIdentification\":\""
              end-to-end-id
              "\",\"response\":\""
              response
              "\"}],\"halLinks\":[]}")})

(defn- load-intent
  [config intent-id]
  (fdb/transact config
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open
                                            txn
                                            "clearbank-outbound-intents")
                                           intent-id)
                          schema/pb->ClearbankOutboundIntent))))

(defn- submission-rejections
  [config dedup-key]
  (fdb/transact config
                (fn [txn]
                  (mapv schema/pb->ClearbankOutboxEvent
                        (fdb/query-records
                         (fdb/open txn "clearbank-outbox")
                         "ClearbankOutboxEvent"
                         "dedup_key"
                         (str dedup-key ":submission-rejected")
                         {:index "ClearbankOutboxEvent_by_dedup_key"})))))

(defn- decode-rejection
  [config event]
  (let [{:keys [schemas]} config]
    (avro/deserialize-same (get schemas "transaction-rejected")
                           (:payload event))))

(defn- assert-failed
  [config intent-id dedup-key failure-kind]
  (let [intent (load-intent config intent-id)
        events (submission-rejections config dedup-key)
        rejection (some->> (first events)
                           (decode-rejection config))]
    (is (= :outbound-intent-status-failed (:status intent)))
    (is (= 1 (count events)) "one submission-rejected event")
    (is (= "transaction-rejected" (:event-name (first events))))
    (is (= dedup-key (:end-to-end-id rejection)))
    (is (= failure-kind (:failure-kind rejection)))
    (is (= "NARR" (:reason-code rejection)))
    (is (= "fps" (:scheme rejection)))
    (is (= :debit-credit-code-debit (:debit-credit-code rejection)))
    (is (false? (:is-return rejection)))))

(deftest outbound-relay-backoff-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [calls (atom 0)
         config (relay-config sys (answering calls (fps-response 500 nil nil)))
         t0 1700000000000]
     (nom-test> [_ (SUT/save-intent config (intent-of "int.5" "e2e-D"))])
     (testing "a 500 retries with the delay doubling from its initial value"
       (outbound/drain-once config t0)
       (is (= 1 @calls))
       (is (= (+ t0 1000) (:next-attempt-at (load-intent config "int.5"))))
       (outbound/drain-once config t0)
       (is (= 1 @calls) "not relayed again before its next attempt")
       (outbound/drain-once config (+ t0 1000))
       (is (= 2 @calls))
       (is (= (+ t0 3000) (:next-attempt-at (load-intent config "int.5"))))
       (outbound/drain-once config (+ t0 2999))
       (is (= 2 @calls) "not relayed again before its next attempt"))
     (testing "a 500 at the last attempt fails the intent"
       (outbound/drain-once config (+ t0 3000))
       (is (= 3 @calls))
       (assert-failed config "int.5" "e2e-D" :failure-kind-undelivered)
       (outbound/drain-once config (+ t0 60000))
       (is (= 3 @calls) "a failed intent is not relayed")))))

(deftest outbound-relay-refusal-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [config (relay-config sys nil)
         relay (fn [res]
                 (assoc config :post-fn (fn [_url _signing-key _request] res)))
         now 1700000000000]
     (testing "a 400 fails the intent on its first attempt"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.6" "e2e-E"))])
       (outbound/drain-once (relay (fps-response 400 nil nil)) now)
       (assert-failed config "int.6" "e2e-E" :failure-kind-refused))
     (testing "a 202 whose transaction is Rejected fails the intent"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.7" "e2e-F"))])
       (outbound/drain-once (relay (fps-response 202 "e2e-F" "Rejected")) now)
       (assert-failed config "int.7" "e2e-F" :failure-kind-refused))
     (testing "a 202 with no transaction for the intent fails the intent"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.8" "e2e-G"))])
       (outbound/drain-once (relay (fps-response 202 "e2e-other" "Accepted"))
                            now)
       (assert-failed config "int.8" "e2e-G" :failure-kind-refused))
     (testing "a 202 whose transaction is Accepted marks the intent sent"
       (nom-test> [_ (SUT/save-intent config (intent-of "int.9" "e2e-H"))])
       (outbound/drain-once (relay (fps-response 202 "e2e-H" "Accepted")) now)
       (is (= :outbound-intent-status-sent
              (:status (load-intent config "int.9"))))
       (is (empty? (submission-rejections config "e2e-H")))))))

(deftest fail-intent-once-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [config (relay-config sys nil)
         rejected (fn [outbox-id]
                    (assoc (event outbox-id "e2e-J:submission-rejected")
                           :event-name
                           "transaction-rejected"))]
     (nom-test> [_ (SUT/save-intent config (intent-of "int.10" "e2e-J"))])
     (testing "the first call fails the intent and writes the event"
       (nom-test> [failed (store/finish config
                                        "int.10"
                                        :outbound-intent-status-pending
                                        :outbound-intent-status-failed
                                        4 (rejected "obx.10"))
                   _ (is (= :outbound-intent-status-failed (:status failed)))]))
     (testing "a second call writes nothing"
       (nom-test> [again (store/finish config
                                       "int.10"
                                       :outbound-intent-status-pending
                                       :outbound-intent-status-failed
                                       5 (rejected "obx.11"))
                   _ (is (= 4 (:attempt-count again)))])
       (is (= :outbound-intent-status-failed
              (:status (load-intent config "int.10"))))
       (is (= ["obx.10"]
              (mapv :outbox-id (submission-rejections config "e2e-J"))))))))

(defn- account-intent
  [intent-id kind dedup-key context]
  {:intent-id intent-id
   :idempotency-key dedup-key
   :kind kind
   :request "{}"
   :context (pr-str context)
   :status :outbound-intent-status-pending
   :attempt-count 0
   :created-at (utility/now)})

(defn- outbox-event
  [config dedup-key]
  (fdb/transact config
                (fn [txn]
                  (some-> (first (fdb/query-records
                                  (fdb/open txn "clearbank-outbox")
                                  "ClearbankOutboxEvent"
                                  "dedup_key"
                                  dedup-key
                                  {:index
                                   "ClearbankOutboxEvent_by_dedup_key"}))
                          schema/pb->ClearbankOutboxEvent))))

(defn- decoded
  [config event]
  (avro/deserialize-same (get (:schemas config) (:event-name event))
                         (:payload event)))

(defn- json-response
  [status body]
  {:status status
   :headers {:content-type "application/json"}
   :body body})

(deftest account-calls-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [config (relay-config sys nil)
         urls (atom [])
         relay (fn [res]
                 (assoc
                  config
                  :post-fn
                  (fn [url _signing-key _request] (swap! urls conj url) res)))
         now 1700000000000
         context {:bank-id "bnk.1" :account-id "acc.1"}]
     (testing "an opened account is reported with its address"
       (nom-test> [_ (SUT/save-intent
                      config
                      (account-intent
                       "int.20" :clearbank-outbound-intent-kind-open-account
                       "open:acc.1" context))])
       (outbound/drain-once
        (relay
         (json-response
          201
          "{\"id\":\"va-1\",\"sortCode\":\"040004\",\"accountNumber\":\"20000001\"}"))
        now)
       (is (= "http://scheme.invalid/v1/virtual-accounts" (last @urls)))
       (is (= :outbound-intent-status-settled
              (:status (load-intent config "int.20"))))
       (let [event (outbox-event config "open:acc.1:payment-account-opened")]
         (is (= "payment-account-opened" (:event-name event)))
         (is (= {:bank-id "bnk.1"
                 :account-id "acc.1"
                 :provider-account-id "va-1"
                 :addresses [{:scheme "scan"
                              :sort-code "040004"
                              :account-number "20000001"}]}
                (decoded config event)))))
     (testing "a declined opening is reported refused, with its reason"
       (nom-test> [_ (SUT/save-intent
                      config
                      (account-intent
                       "int.21" :clearbank-outbound-intent-kind-open-account
                       "open:acc.2" (assoc context :account-id "acc.2")))])
       (outbound/drain-once
        (relay (json-response 422 "{\"detail\":\"The account was declined\"}"))
        now)
       (is (= :outbound-intent-status-failed
              (:status (load-intent config "int.21"))))
       (is (= "The account was declined"
              (:reason (decoded config
                                (outbox-event
                                 config
                                 "open:acc.2:payment-account-refused"))))))
     (testing "a closed account is reported closed"
       (nom-test> [_ (SUT/save-intent
                      config
                      (account-intent
                       "int.22" :clearbank-outbound-intent-kind-close-account
                       "close:acc.1"
                       (assoc context :provider-account-id "va-1")))])
       (outbound/drain-once (relay (json-response 200 "{\"id\":\"va-1\"}")) now)
       (is (= "http://scheme.invalid/v1/virtual-accounts/va-1/close"
              (last @urls)))
       (is (= {:bank-id "bnk.1" :account-id "acc.1"}
              (decoded config
                       (outbox-event config
                                     "close:acc.1:payment-account-closed")))))
     (testing "a close the provider refuses fails with nothing reported"
       (nom-test> [_ (SUT/save-intent
                      config
                      (account-intent
                       "int.23" :clearbank-outbound-intent-kind-close-account
                       "close:acc.3" (assoc context
                                            :account-id "acc.3"
                                            :provider-account-id "va-3")))])
       (outbound/drain-once (relay (json-response 409 "{}")) now)
       (is (= :outbound-intent-status-failed
              (:status (load-intent config "int.23"))))
       (is (nil? (outbox-event config "close:acc.3:payment-account-closed"))))
     (testing "a reissued address is reported under its rotation"
       (nom-test> [_ (SUT/save-intent
                      config
                      (account-intent
                       "int.24" :clearbank-outbound-intent-kind-reissue-address
                       "reissue:acc.1:rot-1" (assoc context
                                                    :provider-account-id "va-1"
                                                    :rotation-key "rot-1")))])
       (outbound/drain-once
        (relay
         (json-response
          200
          "{\"id\":\"va-1\",\"sortCode\":\"040004\",\"accountNumber\":\"20000002\"}"))
        now)
       (is (= "http://scheme.invalid/v1/virtual-accounts/va-1/reissue"
              (last @urls)))
       (is (= "rot-1"
              (:rotation-key
               (decoded config
                        (outbox-event
                         config
                         "reissue:acc.1:rot-1:payment-address-reissued")))))))))

(deftest allocate-account-number-test
  (with-test-system
   [sys "classpath:clearbank-relay/application-test.yml"]
   (let [config (relay-config sys nil)]
     (nom-test> [first-number (SUT/allocate-account-number config)
                 second-number (SUT/allocate-account-number config)
                 _ (testing "numbers are eight digits above the test values"
                     (is (re-matches #"\d{8}" first-number))
                     (is (< 20000000 (Long/parseLong first-number))))
                 _ (testing "and never issued twice"
                     (is (= (inc (Long/parseLong first-number))
                            (Long/parseLong second-number))))]))))
