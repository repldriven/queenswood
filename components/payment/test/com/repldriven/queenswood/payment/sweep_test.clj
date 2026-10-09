(ns com.repldriven.queenswood.payment.sweep-test
  (:require
    [com.repldriven.queenswood.payment.test-system]

    [com.repldriven.queenswood.payment.store :as store]
    [com.repldriven.queenswood.payment.sweep :as SUT]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.test :refer [deftest is testing]]))

(def ^:private bank-id "bnk.sweep")

(defn- outbound-payment
  [payment-id status created-at]
  {:payment-id payment-id
   :idempotency-key (str "idem-" payment-id)
   :scheme-type :scheme-type-fps
   :debtor-account-id "acc.sweep-debtor"
   :creditor-bban "20000087654321"
   :creditor-name "Acme Ltd"
   :currency "GBP"
   :amount 2500
   :status status
   :transaction-id (str "txn." payment-id)
   :created-at created-at
   :created-by {:kind :actor-kind-member :principal-id "usr.payer"}
   :updated-at created-at
   :bank-id bank-id
   :business-day 20260101})

(deftest sweep-reports-a-stuck-pending-payment-test
  (with-test-system
   [sys "classpath:payment/application-test.yml"]
   (let [config {:record-db (system/instance sys [:fdb :record-db])
                 :record-store (system/instance sys [:fdb :store])
                 :report-after-ms 86400000}
         created-at (utility/now)
         now (+ created-at 86400001)
         save (fn [payment-id status]
                (store/save-outbound-payment
                 config
                 (outbound-payment payment-id status created-at)
                 {:change-kind :outbound-payment-change-kind-submit}))]
     (nom-test> [_ (save "pmt.pending" :outbound-payment-status-pending)
                 _ (save "pmt.completed" :outbound-payment-status-completed)
                 _ (save "pmt.failed" :outbound-payment-status-failed)
                 stuck (SUT/sweep-once config now)
                 _ (testing "the sweep reports the pending payment alone"
                     (is (= ["pmt.pending"]
                            (filterv #{"pmt.pending" "pmt.completed"
                                       "pmt.failed"}
                                     (mapv :payment-id stuck)))))]))))
