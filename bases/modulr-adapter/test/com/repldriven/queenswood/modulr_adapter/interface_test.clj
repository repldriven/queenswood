(ns com.repldriven.queenswood.modulr-adapter.interface-test
  "The adapter's HTTP surface and command processor, with a JDK HTTP
  server standing in for Modulr where a notification needs a lookup."
  (:require
    [com.repldriven.queenswood.modulr-adapter.test-system]

    [com.repldriven.queenswood.modulr-adapter.interface :as SUT]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.modulr-relay.interface :as relay]
    [com.repldriven.queenswood.modulr-webhook.interface :as modulr-webhook]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.processor.interface :as processor]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing]])
  (:import
    (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
    (java.net InetSocketAddress)))

(def ^:private webhook-secret "0123456789abcdef0123456789abcdef")

(def ^:dynamic *base-url* nil)

(def ^:dynamic *sys* nil)

(defn- fake-modulr
  "A JDK HTTP server answering every request with `body`."
  [body]
  (doto (HttpServer/create (InetSocketAddress. "localhost" 0) 0)
    (.createContext "/"
                    (reify
                     HttpHandler
                       (handle [_ exchange]
                         (let [^HttpExchange ex exchange
                               bytes (.getBytes ^String body "UTF-8")]
                           (.add (.getResponseHeaders ex)
                                 "Content-Type"
                                 "application/json")
                           (.sendResponseHeaders ex 200 (alength bytes))
                           (with-open [out (.getResponseBody ex)]
                             (.write out bytes))))))
    (.start)))

(defmacro ^:private with-adapter
  [modulr-url & body]
  `(with-test-system
    [sys#
     ["classpath:modulr-adapter/application-test.yml"
      (fn [defs#]
        (-> defs#
            (assoc-in [:system/defs :server :handler] SUT/app)
            (assoc-in [:system/defs :server :interceptors :system/config
                       :modulr-url]
                      ~modulr-url)))]]
    (binding [*sys* sys#
              *base-url* (server/http-local-url
                          (system/instance sys# [:server :jetty-adapter]))]
      ~@body)))

(defn- config
  []
  {:record-db (system/instance *sys* [:fdb :record-db])
   :record-store (system/instance *sys* [:fdb :store])})

(defn- notify
  ([path body] (notify path body webhook-secret))
  ([path body secret]
   (http/request
    {:method :post
     :url (str *base-url* path)
     :headers (merge {"Content-Type" "application/json"}
                     (when secret
                       (modulr-webhook/headers {:key-id "W1" :secret secret}
                                               (modulr-webhook/nonce)
                                               (utility/now))))
     :body (json/write-str body)})))

(defn- outbox-event
  [dedup-key]
  (fdb/transact (config)
                (fn [txn]
                  (some-> (fdb/query-record (fdb/open txn "modulr-outbox")
                                            "ModulrOutboxEvent"
                                            "dedup_key"
                                            dedup-key
                                            {:index
                                             "ModulrOutboxEvent_by_dedup_key"})
                          schema/pb->ModulrOutboxEvent))))

(defn- decoded
  [event]
  (avro/deserialize-same (get (system/instance *sys* [:avro :serde])
                              (:event-name event))
                         (:payload event)))

(defn- payin
  [overrides]
  (merge {:EventName "PAYIN"
          :EventId "e-1"
          :Type "PI_FAST"
          :PaymentId "P100"
          :AccountId "A1"
          :Amount "12.34"
          :Currency "GBP"
          :PayerName "Ford Prefect"
          :Payee {:Identifier {:Type "SCAN"
                               :SortCode "040010"
                               :AccountNumber "00001457"}}
          :PaymentReference "Towel"
          :PaymentAppliedTime "2026-09-27T16:38:06+0000"}
         overrides))

(defn- payout
  [overrides]
  (merge {:EventName "PAYOUT"
          :EventId "e-2"
          :Status "PROCESSED"
          :PaymentId "P200"
          :AccountId "A1"
          :Amount "5.00"
          :EventTime "2026-09-27T16:38:06+0000"}
         overrides))

(defn- save-intent
  [intent]
  (relay/save-intent (config)
                     (merge {:intent-id (str (utility/uuidv7))
                             :request "{}"
                             :nonce "n"
                             :status "sent"
                             :attempts 1
                             :created-at 0}
                            intent)))

(deftest notifications-are-authenticated-test
  (with-adapter
   "http://modulr.invalid"
   (testing "an unsigned notification is refused"
     (is (= 401 (:status (notify "/webhooks/payin" (payin {}) nil)))))
   (testing "one signed with another secret is refused"
     (is (= 401 (:status (notify "/webhooks/payin" (payin {}) "wrong")))))))

(deftest inbound-payment-test
  (with-adapter
   "http://modulr.invalid"
   (is (= 200 (:status (notify "/webhooks/payin" (payin {})))))
   (testing "it settles to the payee's address, in minor units"
     (is (= {:scheme-transaction-id "P100"
             :end-to-end-id "P100"
             :debit-credit-code :debit-credit-code-credit
             :amount 1234
             :creditor-bban "04001000001457"
             :debtor-name "Ford Prefect"}
            (select-keys (decoded (outbox-event "P100:settled"))
                         [:scheme-transaction-id :end-to-end-id
                          :debit-credit-code :amount :creditor-bban
                          :debtor-name]))))
   (testing "a redelivery is accepted and recorded once"
     (is (= 200 (:status (notify "/webhooks/payin" (payin {}))))))
   (testing "an amount finer than two places is refused, nothing written"
     (is (= 400
            (:status (notify "/webhooks/payin"
                             (payin {:PaymentId "P101" :Amount "1.001"})))))
     (is (nil? (outbox-event "P101:settled"))))))

(deftest own-payins-are-not-inbound-payments-test
  (with-adapter
   "http://modulr.invalid"
   (nom-test> [_ (save-intent {:dedup-key "ptr.1" :kind "transfer"})
               _ (save-intent {:dedup-key "ptr.2" :kind "credit"})])
   (testing "the far side of a transfer between accounts"
     (notify "/webhooks/payin"
             (payin {:PaymentId "P110"
                     :Type "INT_INTERC"
                     :SourceExternalReference "ptr-1"}))
     (is (nil? (outbox-event "P110:settled"))))
   (testing "a sandbox credit"
     (notify "/webhooks/payin"
             (payin {:PaymentId "P111" :PaymentReference "ptr-2"}))
     (is (nil? (outbox-event "P111:settled"))))
   (testing "a reissue's move"
     (notify "/webhooks/payin"
             (payin {:PaymentId "P112" :SourceExternalReference "move-x"}))
     (is (nil? (outbox-event "P112:settled"))))))

(deftest payout-test
  (with-adapter
   "http://modulr.invalid"
   (nom-test> [_ (save-intent {:dedup-key "pmt.1"
                               :kind "payment"
                               :context (pr-str {:amount 500 :currency "GBP"})})
               _ (save-intent {:dedup-key "ptr.3"
                               :kind "transfer"
                               :context (pr-str {:bank-id "bnk.1"})})])
   (testing "a processed payment settles and settles its intent"
     (is (= 200
            (:status (notify "/webhooks/payout"
                             (payout {:ExternalReference "pmt-1"})))))
     (is (= {:end-to-end-id "pmt.1"
             :debit-credit-code :debit-credit-code-debit
             :amount 500}
            (select-keys (decoded (outbox-event "P200:settled"))
                         [:end-to-end-id :debit-credit-code :amount])))
     (is (= "settled" (:status (relay/find-intent (config) "pmt.1")))))
   (testing "a failed one is declined"
     (notify "/webhooks/payout"
             (payout {:PaymentId "P201"
                      :Status "ER_INVALID"
                      :ExternalReference "pmt-1"}))
     (is (= :failure-kind-declined
            (:failure-kind (decoded (outbox-event "P201:rejected"))))))
   (testing "a transfer completes"
     (notify "/webhooks/payout"
             (payout {:PaymentId "P202" :ExternalReference "ptr-3"}))
     (is (= {:transfer-id "ptr.3" :bank-id "bnk.1"}
            (select-keys (decoded (outbox-event "P202:completed"))
                         [:transfer-id :bank-id]))))
   (testing "a status that is not final records nothing"
     (notify "/webhooks/payout"
             (payout {:PaymentId "P203"
                      :Status "PENDING_FOR_FUNDS"
                      :ExternalReference "pmt-1"}))
     (is (nil? (outbox-event "P203:settled")))
     (is (nil? (outbox-event "P203:rejected"))))))

(deftest compliance-test
  (let [modulr (fake-modulr (json/write-str {:content
                                             [{:id "P300"
                                               :status "SUBMITTED"
                                               :type "PAYOUT"
                                               :externalReference "pmt-9"
                                               :details {:amount 7.5
                                                         :currency "GBP"
                                                         :destination
                                                         {:type "SCAN"
                                                          :sortCode "203002"
                                                          :accountNumber
                                                          "00004588"}}}]
                                             :page 0
                                             :size 1
                                             :totalPages 1
                                             :totalSize 1}))]
    (try (with-adapter
          (str "http://localhost:" (.getPort (.getAddress modulr)))
          (testing "an outbound held for compliance is held"
            (is (= 200
                   (:status (notify "/webhooks/compliance"
                                    {:EventName "PAYMENTCOMPLIANCESTATUS"
                                     :EventId "e-3"
                                     :AccountBid "A1"
                                     :PaymentBid "P300"
                                     :ComplianceStatus "HELD"}))))
            (is (= {:end-to-end-id "pmt.9"
                    :debit-credit-code :debit-credit-code-debit
                    :amount 750
                    :creditor-bban "20300200004588"}
                   (select-keys (decoded (outbox-event "P300:held"))
                                [:end-to-end-id :debit-credit-code :amount
                                 :creditor-bban]))))
          (testing "and declined"
            (notify "/webhooks/compliance"
                    {:EventName "PAYMENTCOMPLIANCESTATUS"
                     :EventId "e-4"
                     :AccountBid "A1"
                     :PaymentBid "P300"
                     :ComplianceStatus "DECLINED"})
            (is (= "transaction-rejected"
                   (:event-name (outbox-event "P300:rejected"))))))
         (finally (.stop modulr 0)))))

(defn- command
  [name data]
  (let [schemas (system/instance *sys* [:avro :serde])]
    {:command name
     :id (str (utility/uuidv7))
     :payload (avro/serialize (get schemas name) data)}))

(defn- intent-for
  [dedup-key]
  (let [i (relay/find-intent (config) dedup-key)]
    (assoc i
           :context (edn/read-string (:context i))
           :request (json/read-str (:request i) :key-fn keyword))))

(deftest commands-become-intents-test
  (with-adapter
   "http://modulr.invalid"
   (let [p (system/instance *sys* [:modulr-adapter :command-processor])]
     (testing "a payment names its source and a SCAN destination"
       (is (= {:status "ACCEPTED"}
              ;; the adapter's own command processor making its intent
              ;; nosemgrep: brick-test-drives-pipeline
              (processor/process p
                                 (command "submit-payment"
                                          {:payment-id "pmt.5"
                                           :end-to-end-id "pmt.5"
                                           :debtor-bban "04001000000001"
                                           :debtor-provider-account-id "A1"
                                           :creditor-bban "20300200004588"
                                           :creditor-name "Ford Prefect"
                                           :amount 1250
                                           :currency "GBP"
                                           :reference "Towel"
                                           :scheme "fps"}))))
       (let [{:keys [kind request context]} (intent-for "pmt.5")]
         (is (= "payment" kind))
         (is (= {:sourceAccountId "A1"
                 :destination {:type "SCAN"
                               :sortCode "203002"
                               :accountNumber "00004588"
                               :name "Ford Prefect"}
                 :amount 12.5
                 :externalReference "pmt-5"}
                (select-keys request
                             [:sourceAccountId :destination :amount
                              :externalReference])))
         (is (= 1250 (:amount context)))))
     (testing "a redelivered command is accepted once"
       (is (= {:status "ACCEPTED"}
              ;; the adapter's own command processor making its intent
              ;; nosemgrep: brick-test-drives-pipeline
              (processor/process p
                                 (command "submit-payment"
                                          {:payment-id "pmt.5"
                                           :end-to-end-id "pmt.5"
                                           :debtor-bban "04001000000001"
                                           :creditor-bban "20300200004588"
                                           :creditor-name "Ford Prefect"
                                           :amount 1250
                                           :currency "GBP"})))))
     (testing "a transfer between accounts"
       ;; the adapter's own command processor making its intent
       ;; nosemgrep: brick-test-drives-pipeline
       (processor/process p
                          (command "transfer-between-accounts"
                                   {:transfer-id "ptr.5"
                                    :bank-id "bnk.1"
                                    :transaction-id "txn.1"
                                    :debtor-provider-account-id "A1"
                                    :creditor-provider-account-id "A2"
                                    :amount 100
                                    :currency "GBP"}))
       (is (= {:type "ACCOUNT" :id "A2"}
              (:destination (:request (intent-for "ptr.5"))))))
     (testing "money from outside is a sandbox credit"
       ;; the adapter's own command processor making its intent
       ;; nosemgrep: brick-test-drives-pipeline
       (processor/process p
                          (command "transfer-between-accounts"
                                   {:transfer-id "ptr.6"
                                    :bank-id "bnk.1"
                                    :transaction-id "txn.2"
                                    :creditor-provider-account-id "A2"
                                    :amount 100
                                    :currency "GBP"}))
       (let [{:keys [kind request]} (intent-for "ptr.6")]
         (is (= "credit" kind))
         (is (= {:accountId "A2" :description "ptr-6"}
                (select-keys request [:accountId :description])))))
     (testing "an account opening"
       ;; the adapter's own command processor making its intent
       ;; nosemgrep: brick-test-drives-pipeline
       (processor/process p
                          (command "open-payment-account"
                                   {:bank-id "bnk.1"
                                    :account-id "acc.1"
                                    :holder-name "Arthur Dent"
                                    :currency "GBP"
                                    :address-schemes ["scan"]}))
       (is (= {:currency "GBP" :externalReference "acc-1"}
              (:request (intent-for "open:acc.1"))))))))

(deftest name-check-without-a-bank-is-unavailable-test
  (with-adapter
   "http://modulr.invalid"
   (let [res (http/request {:method :post
                            :url (str *base-url* "/cop/outbound")
                            :headers {"Content-Type" "application/json"}
                            :body (json/write-str
                                   {:creditor-name "Ford Prefect"
                                    :account {:sort-code "203002"
                                              :account-number "00004588"}
                                    :account-type "account-type-personal"})})]
     (is (= 200 (:status res)))
     (is (= "match-result-unavailable" (:match-result (http/res->edn res)))))))
