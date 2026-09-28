(ns com.repldriven.queenswood.payment.domain.checks-test
  (:require
    [com.repldriven.queenswood.payment.domain.checks :as SUT]
    [com.repldriven.queenswood.payment.domain.fixtures :as fixtures]
    [com.repldriven.queenswood.payment.domain.inbound :as inbound]
    [com.repldriven.queenswood.payment.domain.internal :as internal]
    [com.repldriven.queenswood.payment.domain.outbound :as outbound]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private non-opened-statuses
  [:cash-account-status-opening
   :cash-account-status-suspended
   :cash-account-status-closing
   :cash-account-status-closed])

(defn- guard-payload
  "The three payload keys `ensure-account-operable` promises."
  [anomaly]
  (select-keys (error/payload anomaly) [:account-id :status :allowed]))

(deftest operable?-test
  (testing "opened alone is operable"
    (is (SUT/operable? (fixtures/account "debtor" "GBP")))
    (doseq [status non-opened-statuses]
      (is (not (SUT/operable? (fixtures/account "debtor" "GBP" status)))
          (str status " is not operable")))))

(deftest account-not-operable-test
  (testing "an opened debtor and creditor still build a transaction"
    (is (not (error/anomaly? (internal/internal-payment->transaction
                              {:idempotency-key "idem-op"
                               :debtor-account-id "debtor"
                               :creditor-account-id "creditor"
                               :currency "GBP"
                               :amount 100}
                              (fixtures/account "debtor" "GBP")
                              (fixtures/account "creditor" "GBP")
                              (fixtures/allow-all)
                              (fixtures/empty-aggregates :internal-payment)))))
    (is (not (error/anomaly? (outbound/outbound-payment->transaction
                              {:idempotency-key "ob-op"
                               :debtor-account-id "debtor"
                               :currency "GBP"
                               :amount 100}
                              (fixtures/account "debtor" "GBP")
                              "internal"
                              (fixtures/allow-all)
                              (fixtures/empty-aggregates :outbound-payment))))))
  (doseq [status non-opened-statuses]
    (testing (str "internal payment, debtor " (name status))
      (let [result (internal/internal-payment->transaction
                    {:idempotency-key "idem-op"
                     :debtor-account-id "debtor"
                     :creditor-account-id "creditor"
                     :currency "GBP"
                     :amount 100}
                    (fixtures/account "debtor" "GBP" status)
                    (fixtures/account "creditor" "GBP")
                    (fixtures/allow-all)
                    (fixtures/empty-aggregates :internal-payment))]
        (is (error/anomaly? result))
        (is (= :payment/debtor-account-not-operable (error/kind result)))
        (is (= {:account-id "debtor"
                :status status
                :allowed #{:cash-account-status-opened}}
               (guard-payload result)))))
    (testing (str "internal payment, creditor " (name status))
      (let [result (internal/internal-payment->transaction
                    {:idempotency-key "idem-op"
                     :debtor-account-id "debtor"
                     :creditor-account-id "creditor"
                     :currency "GBP"
                     :amount 100}
                    (fixtures/account "debtor" "GBP")
                    (fixtures/account "creditor" "GBP" status)
                    (fixtures/allow-all)
                    (fixtures/empty-aggregates :internal-payment))]
        (is (error/anomaly? result))
        (is (= :payment/creditor-account-not-operable (error/kind result)))
        (is (= {:account-id "creditor"
                :status status
                :allowed #{:cash-account-status-opened}}
               (guard-payload result)))))
    (testing (str "outbound payment, debtor " (name status))
      (let [result (outbound/outbound-payment->transaction
                    {:idempotency-key "ob-op"
                     :debtor-account-id "debtor"
                     :currency "GBP"
                     :amount 100}
                    (fixtures/account "debtor" "GBP" status)
                    "internal"
                    (fixtures/allow-all)
                    (fixtures/empty-aggregates :outbound-payment))]
        (is (error/anomaly? result))
        (is (= :payment/debtor-account-not-operable (error/kind result)))
        (is (= {:account-id "debtor"
                :status status
                :allowed #{:cash-account-status-opened}}
               (guard-payload result)))))))

(deftest currency-mismatch-test
  (testing "internal-payment: debtor currency must match payment currency"
    (let [result (internal/internal-payment->transaction
                  {:idempotency-key "idem-2"
                   :debtor-account-id "debtor"
                   :creditor-account-id "creditor"
                   :currency "EUR"
                   :amount 100}
                  (fixtures/account "debtor" "GBP")
                  (fixtures/account "creditor" "EUR")
                  (fixtures/allow-all)
                  (fixtures/empty-aggregates :internal-payment))]
      (is (error/anomaly? result))
      (is (= :payment/currency-mismatch (error/kind result)))))
  (testing "internal-payment: creditor currency must match payment currency"
    (let [result (internal/internal-payment->transaction
                  {:idempotency-key "idem-3"
                   :debtor-account-id "debtor"
                   :creditor-account-id "creditor"
                   :currency "GBP"
                   :amount 100}
                  (fixtures/account "debtor" "GBP")
                  (fixtures/account "creditor" "EUR")
                  (fixtures/allow-all)
                  (fixtures/empty-aggregates :internal-payment))]
      (is (error/anomaly? result))
      (is (= :payment/currency-mismatch (error/kind result)))))
  (testing "inbound-payment: creditor currency must match payment currency"
    (let [result (inbound/inbound-payment->transaction
                  {:scheme-transaction-id "stx-2" :currency "USD" :amount 100}
                  (fixtures/account "creditor" "GBP")
                  "internal"
                  (fixtures/allow-all)
                  (fixtures/empty-aggregates :inbound-payment))]
      (is (error/anomaly? result))
      (is (= :payment/currency-mismatch (error/kind result)))))
  (testing "outbound-payment: debtor currency must match payment currency"
    (let [result (outbound/outbound-payment->transaction
                  {:idempotency-key "ob-2"
                   :debtor-account-id "debtor"
                   :currency "USD"
                   :amount 100}
                  (fixtures/account "debtor" "GBP")
                  "internal"
                  (fixtures/allow-all)
                  (fixtures/empty-aggregates :outbound-payment))]
      (is (error/anomaly? result))
      (is (= :payment/currency-mismatch (error/kind result))))))

(defn- ts
  "Epoch-millis from an ISO-8601 instant string."
  ^long [s]
  (.toEpochMilli (java.time.Instant/parse s)))

(defn- day
  "Epoch-day from an ISO-8601 local-date string."
  ^long [s]
  (.toEpochDay (java.time.LocalDate/parse s)))

(deftest current-business-day-test
  (testing "UTC midnight cutoff = calendar epoch-day"
    (is (= (day "2026-01-15")
           (SUT/current-business-day (ts "2026-01-15T12:00:00Z")
                                     {:zone "UTC" :hour-of-day 0}))))
  (testing "Europe/London 17:00 cutoff: 16:00 GMT rolls to previous day"
    ;; 2026-01-15 is winter — Europe/London = GMT, so 16:00 UTC =
    ;; 16:00 London, before the cutoff.
    (is (= (day "2026-01-14")
           (SUT/current-business-day (ts "2026-01-15T16:00:00Z")
                                     {:zone "Europe/London" :hour-of-day 17}))))
  (testing "Europe/London 17:00 cutoff: 17:00 GMT counts as current day"
    (is (= (day "2026-01-15")
           (SUT/current-business-day (ts "2026-01-15T17:00:00Z")
                                     {:zone "Europe/London" :hour-of-day 17}))))
  (testing "zone shifts the date boundary"
    ;; 2026-01-15 23:30 UTC. In Asia/Tokyo (UTC+9) that's 2026-01-16 08:30.
    ;; With cutoff 0, business-day = 2026-01-16.
    (is (= (day "2026-01-16")
           (SUT/current-business-day (ts "2026-01-15T23:30:00Z")
                                     {:zone "Asia/Tokyo" :hour-of-day 0})))))
