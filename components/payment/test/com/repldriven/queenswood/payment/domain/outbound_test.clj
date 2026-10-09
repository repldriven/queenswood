(ns com.repldriven.queenswood.payment.domain.outbound-test
  (:require
    [com.repldriven.queenswood.payment.domain.fixtures :as fixtures]
    [com.repldriven.queenswood.payment.domain.outbound :as SUT]
    [com.repldriven.queenswood.payment.domain.provider-transfer :as
     provider-transfer]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(defn- leg
  "Returns the leg matching both side and balance-status, or nil — needed
  once a transaction carries several legs across balance buckets."
  [tx leg-side balance-status]
  (some (fn [l]
          (when (and (= leg-side (:side l))
                     (= balance-status (:balance-status l)))
            l))
        (:legs tx)))

(deftest outbound-payment->transaction-test
  (let [tx (SUT/outbound-payment->transaction {:idempotency-key "ob-1"
                                               :debtor-account-id "debtor"
                                               :currency "GBP"
                                               :amount 250
                                               :reference "Outbound"}
                                              (fixtures/account "debtor" "GBP")
                                              "internal"
                                              (fixtures/allow-all)
                                              (fixtures/empty-aggregates
                                               :outbound-payment))]
    (testing "envelope shape"
      (is (= "ob-1" (:idempotency-key tx)))
      (is (= :transaction-type-outbound-transfer (:transaction-type tx))))
    (testing "reserves: customer + GL 1200 both on pending-outgoing"
      ;; Submit doesn't post — it reserves. The customer's funds move to
      ;; their pending-outgoing bucket (available drops, posted untouched)
      ;; and the bank's 1200 claim is pending too, so nothing hits a posted
      ;; bucket and the trial balance is undisturbed until settlement.
      (let [debit (leg tx :leg-side-debit :balance-status-pending-outgoing)
            credit (leg tx :leg-side-credit :balance-status-pending-outgoing)]
        (is (= "debtor" (:account-id debit)))
        (is (= :balance-type-default (:balance-type debit)))
        (is (= 250 (:amount debit)))
        (is (= "internal" (:account-id credit)))
        (is (= :balance-type-default (:balance-type credit)))
        (is (= 250 (:amount credit)))))
    (testing "legs balance" (is (fixtures/balanced? tx)))))

(deftest outbound-settlement->transaction-test
  (let [payment {:payment-id "pmt-1"
                 :debtor-account-id "debtor"
                 :currency "GBP"
                 :amount 250
                 :reference "Beer and nuts"}
        tx (SUT/outbound-settlement->transaction payment
                                                 (fixtures/account "debtor"
                                                                   "GBP")
                                                 "1200"
                                                 "1100")]
    (testing "carries the payment reference onto the settled debit"
      (is (= "Beer and nuts" (:reference tx))))
    (testing "clears the reservation and posts the real outflow"
      ;; Customer: credit pending-outgoing (clear the reservation) and
      ;; debit posted (the money now actually leaves).
      (let [clear (leg tx :leg-side-credit :balance-status-pending-outgoing)
            post (leg tx :leg-side-debit :balance-status-posted)]
        (is (= "debtor" (:account-id clear)))
        (is (= 250 (:amount clear)))
        (is (= "debtor" (:account-id post)))
        (is (= 250 (:amount post)))))
    (testing "drains 1200 (pending claim) out via 1100 (posted)"
      (let [drain (leg tx :leg-side-debit :balance-status-pending-outgoing)
            out (leg tx :leg-side-credit :balance-status-posted)]
        (is (= "1200" (:account-id drain)))
        (is (= "1100" (:account-id out)))))
    (testing "legs balance" (is (fixtures/balanced? tx)))))

(deftest outbound-reversal->transaction-test
  (let [payment {:payment-id "pmt-2"
                 :debtor-account-id "debtor"
                 :currency "GBP"
                 :amount 250}
        tx (SUT/outbound-reversal->transaction payment
                                               (fixtures/account "debtor" "GBP")
                                               "1200")]
    (testing "releases the reservation — every leg on pending-outgoing"
      ;; The money never left the debtor's posted balance, so nothing
      ;; posted is reversed: just drain 1200 and release the reservation.
      (is (every? #(= :balance-status-pending-outgoing (:balance-status %))
                  (:legs tx)))
      (let [drain (leg tx :leg-side-debit :balance-status-pending-outgoing)
            release (leg tx :leg-side-credit :balance-status-pending-outgoing)]
        (is (= "1200" (:account-id drain)))
        (is (= 250 (:amount drain)))
        (is (= "debtor" (:account-id release)))
        (is (= 250 (:amount release)))))
    (testing "legs balance" (is (fixtures/balanced? tx)))))

(deftest outbound-return->transaction-test
  (let [payment {:payment-id "pmt-3"
                 :debtor-account-id "debtor"
                 :currency "GBP"
                 :amount 250
                 :reference "Beer and nuts"}
        tx (SUT/outbound-return->transaction payment
                                             (fixtures/account "debtor" "GBP")
                                             "1100"
                                             240)]
    (testing "is an outbound return through the debtor's account"
      (is (= :transaction-type-outbound-return (:transaction-type tx)))
      (is (= "debtor" (:scheme-account-id tx)))
      (is (= "return-out-pmt-3" (:idempotency-key tx))))
    (testing "brings the returned amount back from 1100 to the debtor"
      (let [in (leg tx :leg-side-debit :balance-status-posted)
            back (leg tx :leg-side-credit :balance-status-posted)]
        (is (= "1100" (:account-id in)))
        (is (= "debtor" (:account-id back)))
        (is (= 240 (:amount back)))))
    (testing "legs balance" (is (fixtures/balanced? tx)))
    (testing "nets to nothing at the provider"
      (is (= []
             (provider-transfer/provider-transfers
              tx
              {:cash-accounts {"debtor" "debtor"}
               :cash-at-correspondent-id "1100"
               :scheme-account-id "debtor"
               :own-funds "house"}))))))

(deftest returned-outbound-payment-test
  (let [payment {:payment-id "pmt.1" :status :outbound-payment-status-completed}
        returned (SUT/returned-outbound-payment payment
                                                {:reason-code "AC04"
                                                 :reason "Account closed"})]
    (is (= :outbound-payment-status-returned (:status returned)))
    (is (= "AC04" (:returned-reason-code returned)))
    (is (= "Account closed" (:returned-reason returned)))
    (is (= (:returned-at returned) (:updated-at returned)))
    (is (not (contains? (SUT/returned-outbound-payment payment
                                                       {:reason-code "NARR"})
                        :returned-reason)))))

(deftest settleable-outbound?-test
  (testing "pending and held settle"
    (is (SUT/settleable-outbound? {:status :outbound-payment-status-pending}))
    (is (SUT/settleable-outbound? {:status :outbound-payment-status-held})))
  (testing "completed and failed do not"
    (doseq [status [:outbound-payment-status-completed
                    :outbound-payment-status-failed]]
      (is (not (SUT/settleable-outbound? {:status status}))
          (str status " is not settleable")))))

(deftest completed-outbound-payment-test
  (testing "flips :status to completed"
    (let [pending {:payment-id "pmt-1"
                   :status :outbound-payment-status-pending
                   :amount 250
                   :created-at 1700000000000
                   :updated-at 1700000000000}
          completed (SUT/completed-outbound-payment pending)]
      (is (= :outbound-payment-status-completed (:status completed)))
      (testing "preserves other fields"
        (is (= "pmt-1" (:payment-id completed)))
        (is (= 250 (:amount completed))))
      (testing "bumps :updated-at past the original, and records when"
        (is (>= (:updated-at completed) (:updated-at pending)))
        (is (= (:completed-at completed) (:updated-at completed)))))))

(def ^:private sweep-thresholds {:report-after-ms 86400000})

(defn- outbound
  [payment-id payment-status created-at]
  {:payment-id payment-id
   :bank-id "bnk.sweep"
   :status payment-status
   :created-at created-at})

(deftest stuck-outbound-test
  (let [created-at 1700000000000
        past-report (+ created-at 86400001)
        young-pending
        (outbound "pmt.young" :outbound-payment-status-pending past-report)
        old-pending
        (outbound "pmt.pending" :outbound-payment-status-pending created-at)
        old-held (outbound "pmt.held" :outbound-payment-status-held created-at)
        old-completed
        (outbound "pmt.completed" :outbound-payment-status-completed created-at)
        old-failed
        (outbound "pmt.failed" :outbound-payment-status-failed created-at)
        payments [young-pending old-pending old-held old-completed old-failed]]
    (testing "past the report threshold, old pending and held report"
      (is (= [{:payment-id "pmt.pending"
               :bank-id "bnk.sweep"
               :status :outbound-payment-status-pending
               :age-ms 86400001}
              {:payment-id "pmt.held"
               :bank-id "bnk.sweep"
               :status :outbound-payment-status-held
               :age-ms 86400001}]
             (SUT/stuck-outbound payments past-report sweep-thresholds))))
    (testing "a young pending payment gives nothing"
      (is
       (= []
          (SUT/stuck-outbound [young-pending] past-report sweep-thresholds))))))

(deftest check-scheme-test
  (let [provider {:schemes ["fps"]}]
    (testing "a scheme the provider carries passes"
      (is (nil? (SUT/check-scheme "fps" provider))))
    (testing "one it does not is refused"
      (let [res (SUT/check-scheme "chaps" provider)]
        (is (error/rejection? res))
        (is (= :payment/unsupported-scheme (error/kind res)))
        (is (= "chaps" (:scheme (error/payload res))))))))

(deftest failed-outbound-payment-test
  (let [payment {:payment-id "pmt.1" :status :outbound-payment-status-pending}]
    (testing "the event's failure kind and reason code are recorded"
      (let [failed (SUT/failed-outbound-payment
                    payment
                    {:failure-kind :failure-kind-refused
                     :reason-code "AC01"
                     :cancellation-reason "HTTP 400"})]
        (is (= :outbound-payment-status-failed (:status failed)))
        (is (= :outbound-payment-failed-kind-refused (:failed-kind failed)))
        (is (= "AC01" (:failed-reason-code failed)))
        (is (= "HTTP 400" (:failed-reason failed)))))
    (testing "an event that predates them is a decline coded NARR"
      (let [failed (SUT/failed-outbound-payment payment
                                                {:cancellation-code
                                                 "SCENARIO_REJECTED"})]
        (is (= :outbound-payment-failed-kind-declined (:failed-kind failed)))
        (is (= "NARR" (:failed-reason-code failed)))
        (is (not (contains? failed :failed-reason)))))))
