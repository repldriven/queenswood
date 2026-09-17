(ns com.repldriven.queenswood.payment.sweep-test
  (:require
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.payment.store :as store]
    [com.repldriven.queenswood.payment.sweep :as SUT]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.message-bus.interface :as message-bus]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def
  ^{:private true
    :doc
    "Must match cash-account.store/store-name, the store get-account
  reads."}
  cash-accounts-store
  "cash-accounts")

(def ^:private bank-id "bnk.sweep")
(def ^:private debtor-account-id "acc.sweep-debtor")
(def ^:private sort-code "040404")
(def ^:private account-number "12345678")
(def ^:private sentinel "sweep-test-sentinel")

(defn- debtor-account
  [created-at]
  {:bank-id bank-id
   :account-id debtor-account-id
   :account-type :account-type-business
   :party-id "pty.sweep"
   :product-id "prd.sweep"
   :version-id "prv.1"
   :product-type :product-type-sub-ledger-current
   :name debtor-account-id
   :currency "GBP"
   :account-status :cash-account-status-opened
   :payment-addresses [{:scheme :payment-address-scheme-scan
                        :scan {:sort-code sort-code
                               :account-number account-number}}]
   :bban (str sort-code account-number)
   :created-at created-at
   :updated-at created-at})

(defn- outbound-payment
  [payment-id payment-status created-at]
  {:payment-id payment-id
   :idempotency-key (str "idem-" payment-id)
   :scheme "fps"
   :debtor-account-id debtor-account-id
   :creditor-bban "20000087654321"
   :creditor-name "Acme Ltd"
   :currency "GBP"
   :amount 2500
   :payment-status payment-status
   :transaction-id (str "txn." payment-id)
   :created-at created-at
   :updated-at created-at
   :bank-id bank-id
   :business-day 20260101})

(defn- save-account
  [config account]
  (fdb/transact config
                (fn [txn]
                  (fdb/save-record (fdb/open txn cash-accounts-store)
                                   (schema/CashAccount->java account))
                  nil)
                :test/save-account
                "Failed to save the debtor account"))

(deftest sweep-republishes-a-stuck-pending-payment-test
  (with-test-system
   [sys "classpath:payment/application-test.yml"]
   (let [bus (system/instance sys [:payment :bus])
         schemas (system/instance sys [:avro :serde])
         config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])
                 :schemas schemas
                 :bus bus
                 :scheme-payment-command-channel :schemes-payment-command
                 :republish-after-ms 900000
                 :report-after-ms 86400000}
         created-at (utility/now)
         now (+ created-at 86400001)
         published (atom [])
         drained (promise)]
     (message-bus/subscribe bus
                            :schemes-payment-command
                            (fn [command]
                              (if (= sentinel (:command command))
                                (deliver drained true)
                                (swap! published conj command))))
     (nom-test> [_ (save-account config (debtor-account created-at))
                 _ (store/save-outbound-payment
                    config
                    (outbound-payment "pmt.pending"
                                      :outbound-payment-status-pending
                                      created-at))
                 _ (store/save-outbound-payment
                    config
                    (outbound-payment "pmt.completed"
                                      :outbound-payment-status-completed
                                      created-at))
                 _ (store/save-outbound-payment config
                                                (outbound-payment
                                                 "pmt.failed"
                                                 :outbound-payment-status-failed
                                                 created-at))
                 actions (SUT/sweep-once config now)
                 _ (testing "the sweep reports the pending payment"
                     (is (= ["pmt.pending"]
                            (mapv :payment-id (:report actions)))))
                 _ (message-bus/send bus
                                     :schemes-payment-command
                                     {:command sentinel})
                 _ (is
                    (true? (deref drained 5000 false))
                    "every command published before the sentinel is delivered")
                 _ (testing "exactly one submit-payment is published"
                     (is (= ["submit-payment"] (mapv :command @published))))
                 command (first @published)
                 submit (avro/deserialize-same (get schemas "submit-payment")
                                               (:payload command))
                 _ (testing "its end-to-end id is the pending payment's"
                     (is (= "pmt.pending" (:end-to-end-id submit)))
                     (is (= "pmt.pending" (:payment-id submit))))
                 _ (testing
                     "it carries the debtor's BBAN, read from the account"
                     (is (= (str sort-code account-number)
                            (:debtor-bban submit))))]))))
