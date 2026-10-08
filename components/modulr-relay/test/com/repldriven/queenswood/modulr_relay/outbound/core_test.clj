(ns ^:eftest/synchronized
    com.repldriven.queenswood.modulr-relay.outbound.core-test
  "Drives the runner with a `post-fn` standing in for Modulr, which the
  runner takes as configuration, so nothing is redefined for the JVM. A
  pass drains every intent in the store the tests share, so they run one
  at a time."
  (:require
    [com.repldriven.queenswood.modulr-relay.test-system]

    [com.repldriven.queenswood.modulr-relay.outbound.core :as SUT]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.modulr-relay.interface :as relay]
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
   :body body})

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
   :modulr-url "http://modulr.invalid"
   :customer-id "C1"
   :delivery-policy {:default {:initial-backoff-ms 1000
                               :backoff-growth 2
                               :max-backoff-ms 60000
                               :max-attempts 3
                               :max-age-ms 86400000}
                     :breaker {:failure-threshold 1000
                               :cool-down-ms 1000
                               :max-cool-down-ms 1000
                               :probe-lease-ms 1000}}
   :reconcile-after-ms 60000
   :post-fn post-fn})

(defn- intent
  [intent-id kind dedup-key request context]
  {:intent-id intent-id
   :dedup-key dedup-key
   :kind kind
   :request request
   :nonce (str "nonce-" intent-id)
   :status :outbound-intent-status-pending
   :attempts 0
   :created-at (utility/now)
   :context (pr-str context)})

(defn- load-intent
  [config intent-id]
  (fdb/transact config
                (fn [txn]
                  (some-> (fdb/load-record (fdb/open txn
                                                     "modulr-outbound-intents")
                                           intent-id)
                          schema/pb->ModulrOutboundIntent))))

(defn- outbox-event
  [config dedup-key]
  (fdb/transact config
                (fn [txn]
                  (some-> (fdb/query-record (fdb/open txn "modulr-outbox")
                                            "ModulrOutboxEvent"
                                            "dedup_key"
                                            dedup-key
                                            {:index
                                             "ModulrOutboxEvent_by_dedup_key"})
                          schema/pb->ModulrOutboxEvent))))

(defn- decoded
  [config event]
  (avro/deserialize-same (get (:schemas config) (:event-name event))
                         (:payload event)))

(deftest payment-sent-then-retried-as-the-same-request-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [calls (atom [])
         answers
         (atom [{:status 503 :body ""}
                (json-response 201 "{\"id\":\"P9\",\"status\":\"SUBMITTED\"}")])
         config (runner-config sys
                               (recording calls
                                          (fn [_]
                                            (let [a (first @answers)]
                                              (swap! answers rest)
                                              a))))
         request "{\"amount\":1.50}"]
     (nom-test> [_ (relay/save-intent
                    config
                    (intent "int.p1"
                            :modulr-outbound-intent-kind-payment
                            "pmt.1"
                            request
                            {:bank-id "bnk.1" :amount 150 :currency "GBP"}))])
     (SUT/drain-once config 0)
     (testing "a failed call parks the intent for another attempt"
       (let [i (load-intent config "int.p1")]
         (is (= :outbound-intent-status-pending (:status i)))
         (is (= 1 (:attempts i)))))
     (SUT/drain-once config 10000)
     (testing "the retry sends the first attempt's nonce, marked a retry"
       (let [[first-call retry] @calls]
         (is (= "nonce-int.p1" (:nonce first-call) (:nonce retry)))
         (is (not (:retry? first-call)))
         (is (:retry? retry))
         (is (= request (:raw-body retry)))))
     (testing "an accepted payment is sent, with Modulr's id kept"
       (let [i (load-intent config "int.p1")]
         (is (= :outbound-intent-status-sent (:status i)))
         (is (= "P9" (:provider-payment-id i))))))))

(deftest refused-payment-is-rejected-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [config (runner-config
                 sys
                 (recording
                  (atom [])
                  (fn [_]
                    (json-response
                     400
                     "[{\"code\":\"INVALID\",\"message\":\"Bad\"}]"))))]
     (nom-test> [_ (relay/save-intent
                    config
                    (intent "int.p2"
                            :modulr-outbound-intent-kind-payment "pmt.2"
                            "{}" {:bank-id "bnk.1"}))])
     (SUT/drain-once config 0)
     (is (= :outbound-intent-status-failed
            (:status (load-intent config "int.p2"))))
     (let [event (outbox-event config "pmt.2:submission-rejected")
           data (decoded config event)]
       (is (= "transaction-rejected" (:event-name event)))
       (is (= :failure-kind-refused (:failure-kind data)))
       (is (= "Bad" (:cancellation-reason data)))))))

(deftest reconciliation-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [lookups (atom 0)
         status (atom "PENDING_FOR_FUNDS")
         config (runner-config
                 sys
                 (fn [_ {:keys [method]}]
                   (if (= :get method)
                     (do (swap! lookups inc)
                         (json-response
                          200
                          (str "{\"content\":[{\"id\":\"P3\",\"status\":\""
                               @status
                               "\"}]}")))
                     (json-response 201 "{\"id\":\"P3\"}"))))]
     (nom-test> [_ (relay/save-intent
                    config
                    (intent "int.p3"
                            :modulr-outbound-intent-kind-payment "pmt.3"
                            "{}"
                            {:bank-id "bnk.1" :amount 250 :currency "GBP"}))])
     (SUT/drain-once config 0)
     (testing "nothing is asked before reconcile-after-ms"
       (SUT/drain-once config 1000)
       (is (zero? @lookups)))
     (testing "a payment still pending is asked again later"
       (SUT/drain-once config 60001)
       (is (= 1 @lookups))
       (is (= :outbound-intent-status-sent
              (:status (load-intent config "int.p3")))))
     (testing "a processed payment settles under the webhook's dedup key"
       (reset! status "PROCESSED")
       (SUT/drain-once config 200000)
       (is (= :outbound-intent-status-settled
              (:status (load-intent config "int.p3"))))
       (let [event (outbox-event config "P3:settled")
             data (decoded config event)]
         (is (= "transaction-settled" (:event-name event)))
         (is (= 250 (:amount data)))
         (is (= "pmt.3" (:end-to-end-id data))))))))

(deftest a-webhook-first-leaves-reconciliation-nothing-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [lookups (atom 0)
         config (runner-config sys
                               (fn [_ {:keys [method]}]
                                 (if (= :get method)
                                   (do (swap! lookups inc)
                                       (json-response 200 "{\"content\":[]}"))
                                   (json-response 201 "{\"id\":\"P4\"}"))))]
     (nom-test> [_ (relay/save-intent
                    config
                    (intent "int.p4"
                            :modulr-outbound-intent-kind-payment "pmt.4"
                            "{}" {:bank-id "bnk.1"}))])
     (SUT/drain-once config 0)
     (nom-test> [_ (relay/save-event config
                                     {:outbox-id "obx.p4"
                                      :dedup-key "P4:settled"
                                      :event-name "transaction-settled"
                                      :payload (.getBytes "x")
                                      :created-at 0}
                                     "pmt.4")])
     (SUT/drain-once config 200000)
     (is (= :outbound-intent-status-settled
            (:status (load-intent config "int.p4"))))
     (is (zero? @lookups)))))

(deftest credit-completes-at-once-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [calls (atom [])
         config
         (runner-config sys (recording calls (fn [_] {:status 200 :body ""})))]
     (nom-test> [_ (relay/save-intent
                    config
                    (intent "int.c1"
                            :modulr-outbound-intent-kind-credit "ptr.1"
                            "{}"
                            {:bank-id "bnk.1" :amount 150 :currency "GBP"}))])
     (SUT/drain-once config 0)
     (is (= "/credit" (:path (first @calls))))
     (is (= :outbound-intent-status-settled
            (:status (load-intent config "int.c1"))))
     (is (= {:transfer-id "ptr.1" :bank-id "bnk.1"}
            (select-keys (decoded config
                                  (outbox-event config "ptr.1:completed"))
                         [:transfer-id :bank-id]))))))

(def ^:private opened
  (str "{\"id\":\"A2\",\"status\":\"ACTIVE\",\"identifiers\":"
       "[{\"type\":\"SCAN\",\"sortCode\":\"000000\","
       "\"accountNumber\":\"00000002\"}]}"))

(deftest open-account-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [calls (atom [])
         config (runner-config sys
                               (recording calls
                                          (fn [_] (json-response 201 opened))))]
     (nom-test> [_ (relay/save-intent
                    config
                    (intent "int.o1"
                            :modulr-outbound-intent-kind-open-account
                            "open:acc.1"
                            "{}" {:bank-id "bnk.1" :account-id "acc.1"}))])
     (SUT/drain-once config 0)
     (is (= "/customers/C1/accounts" (:path (first @calls))))
     (is (= {:bank-id "bnk.1"
             :account-id "acc.1"
             :provider-account-id "A2"
             :addresses
             [{:scheme "scan" :sort-code "000000" :account-number "00000002"}]}
            (decoded config
                     (outbox-event config
                                   "open:acc.1:payment-account-opened")))))))

(deftest reissue-moves-the-balance-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [calls (atom [])
         config
         (runner-config
          sys
          (recording
           calls
           (fn [{:keys [method path]}]
             (cond
              (= :get method)
              (json-response
               200
               "{\"id\":\"A1\",\"balance\":\"12.34\",\"currency\":\"GBP\"}")

              (= "/customers/C1/accounts" path)
              (json-response 201 opened)

              :else
              (json-response 200 "{}")))))]
     (nom-test> [_ (relay/save-intent
                    config
                    (intent "int.r1"
                            :modulr-outbound-intent-kind-reissue-address
                            "reissue:acc.1:k1"
                            "{}" {:bank-id "bnk.1"
                                  :account-id "acc.1"
                                  :provider-account-id "A1"
                                  :rotation-key "k1"}))])
     (doseq [t (range 6)] (SUT/drain-once config t))
     (testing "block, read, open, move and close, in that order"
       (is (= [[:post "/accounts/A1/block"] [:get "/accounts/A1"]
               [:post "/customers/C1/accounts"] [:post "/payments"]
               [:post "/accounts/A1/close"]]
              (mapv (juxt :method :path) @calls))))
     (testing "the move takes the whole balance to the new account"
       (let [move (nth @calls 3)]
         (is (= {:sourceAccountId "A1"
                 :destination {:type "ACCOUNT" :id "A2"}
                 :amount 12.34M}
                (select-keys (:body move)
                             [:sourceAccountId :destination :amount])))))
     (testing "each step is signed with a nonce of its own"
       (is (= 5 (count (distinct (map :nonce @calls))))))
     (testing "the new address is reported"
       (let [data (decoded config
                           (outbox-event
                            config
                            "reissue:acc.1:k1:payment-address-reissued"))]
         (is (= "A2" (:provider-account-id data)))
         (is (= "k1" (:rotation-key data)))))
     (is (= :outbound-intent-status-settled
            (:status (load-intent config "int.r1"))))
     (is (= "done"
            (:step (edn/read-string (:context (load-intent config
                                                           "int.r1")))))))))

(deftest a-refused-close-fails-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [config (runner-config sys
                               (recording
                                (atom [])
                                (fn [_]
                                  (json-response
                                   400
                                   "{\"message\":\"Balance is not zero\"}"))))]
     (nom-test> [_ (relay/save-intent
                    config
                    (intent "int.c2"
                            :modulr-outbound-intent-kind-close-account
                            "close:acc.2"
                            "{}" {:bank-id "bnk.1"
                                  :account-id "acc.2"
                                  :provider-account-id "A2"}))])
     (SUT/drain-once config 0)
     (testing "a refused close fails rather than being tried again"
       (let [i (load-intent config "int.c2")]
         (is (= :outbound-intent-status-failed (:status i)))
         (is (= 1 (:attempts i))))))))

(deftest a-close-waits-for-the-transfer-before-it-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [calls (atom [])
         config (runner-config sys
                               (recording
                                calls
                                (fn [{:keys [path]}]
                                  (if (= "/payments" path)
                                    (json-response
                                     201
                                     "{\"id\":\"P1\",\"status\":\"SUBMITTED\"}")
                                    (json-response 200 "{}")))))
         paths (fn []
                 (filterv #{"/payments" "/accounts/A1/close"}
                          (mapv :path @calls)))]
     (nom-test> [_ (relay/save-intent
                    config
                    (assoc (intent "int.1-transfer"
                                   :modulr-outbound-intent-kind-transfer
                                   "ptr.wait.1"
                                   "{}" {:bank-id "bnk.1"
                                         :amount 150
                                         :currency "GBP"
                                         :debtor-account-id "acc.wait.1"
                                         :creditor-account-id "acc.wait.2"
                                         :debtor-provider-account-id "A1"
                                         :creditor-provider-account-id "A2"})
                           :subjects
                           ["acc.wait.1" "acc.wait.2"]))
                 _ (relay/save-intent
                    config
                    (assoc (intent "int.2-close"
                                   :modulr-outbound-intent-kind-close-account
                                   "close:acc.wait.1"
                                   "{}" {:bank-id "bnk.1"
                                         :account-id "acc.wait.1"
                                         :provider-account-id "A1"})
                           :subjects
                           ["acc.wait.1"]))])
     (SUT/drain-once config 0)
     (testing "the transfer is sent and the close held while it is unsettled"
       (is (= ["/payments"] (paths)))
       (is (= :outbound-intent-status-pending
              (:status (load-intent config "int.2-close")))))
     (relay/save-event config
                       {:outbox-id "obx.wait.1"
                        :dedup-key "P1:wait:settled"
                        :event-name "transfer-completed"
                        :payload (avro/serialize (get (:schemas config)
                                                      "transfer-completed")
                                                 {:transfer-id "ptr.wait.1"
                                                  :bank-id "bnk.1"
                                                  :timestamp-completed 0})
                        :created-at 0}
                       "ptr.wait.1")
     (SUT/drain-once config 0)
     (testing "once the transfer settles the close is made"
       (is (= ["/payments" "/accounts/A1/close"] (paths)))
       (is (= :outbound-intent-status-settled
              (:status (load-intent config "int.2-close"))))))))

(defn- opened-as
  [provider-account-id]
  (json-response 201
                 (str "{\"id\":\"" provider-account-id
                      "\","
                      "\"identifiers\":[{\"type\":\"SCAN\","
                      "\"sortCode\":\"040010\","
                      "\"accountNumber\":\"00000001\"}]}")))

(deftest transfer-names-the-provider-accounts-its-opens-recorded-test
  (with-test-system
   [sys "classpath:modulr-relay/application-test.yml"]
   (let [calls (atom [])
         config (runner-config
                 sys
                 (recording calls
                            (fn [{:keys [path raw-body]}]
                              (cond
                               (= "/customers/C1/accounts" path)
                               (if (re-find #"acc-names-1" raw-body)
                                 (opened-as "A1")
                                 (opened-as "A2"))

                               :else
                               (json-response
                                201
                                "{\"id\":\"P1\",\"status\":\"SUBMITTED\"}")))))
         open (fn [intent-id account-id]
                (intent intent-id
                        :modulr-outbound-intent-kind-open-account
                        (str "open:" account-id)
                        (json/write-str {:currency "GBP"
                                         :externalReference (relay/->reference
                                                             account-id)})
                        {:bank-id "bnk.1" :account-id account-id}))]
     (nom-test> [_ (relay/save-intent config
                                      (open "int.names.o1" "acc.names.1"))
                 _ (relay/save-intent config
                                      (open "int.names.o2" "acc.names.2"))
                 _ (relay/save-intent
                    config
                    (intent "int.names.t1"
                            :modulr-outbound-intent-kind-transfer "ptr.names.1"
                            "{}" {:bank-id "bnk.1"
                                  :amount 150
                                  :currency "GBP"
                                  :debtor-account-id "acc.names.1"
                                  :creditor-account-id "acc.names.2"}))])
     (SUT/drain-once config 0)
     (testing "the transfer moves money between the accounts its opens made"
       (let [transfer (some (fn [{:keys [raw-body] :as call}]
                              (when (re-find #"ptr-names-1" (str raw-body))
                                call))
                            @calls)
             body (json/read-str (:raw-body transfer) :key-fn keyword)]
         (is (= "/payments" (:path transfer)))
         (is (= "A1" (:sourceAccountId body)))
         (is (= {:type "ACCOUNT" :id "A2"} (:destination body)))))
     (testing
       "a transfer naming an account no open has made yet waits, counting no attempt"
       (nom-test> [_ (relay/save-intent config
                                        (intent
                                         "int.names.t2"
                                         :modulr-outbound-intent-kind-transfer
                                         "ptr.names.2"
                                         "{}" {:bank-id "bnk.1"
                                               :amount 150
                                               :currency "GBP"
                                               :debtor-account-id "acc.names.1"
                                               :creditor-account-id "acc.9"}))])
       (SUT/drain-once config 0)
       (let [i (load-intent config "int.names.t2")]
         (is (= :outbound-intent-status-pending (:status i)))
         (is (= 0 (:attempts i)))
         (is (pos? (:next-attempt-at i))))))))
