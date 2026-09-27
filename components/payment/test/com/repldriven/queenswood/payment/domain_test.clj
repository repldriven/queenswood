(ns com.repldriven.queenswood.payment.domain-test
  "Pure-function tests for the payment-to-transaction builders. No
  FDB, no processor — these pin the leg shapes (which balance-type,
  which status, which side) the brick's handlers rely on."
  (:require
    [com.repldriven.queenswood.payment.domain :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(defn- side
  "Returns the leg on the requested side, or nil."
  [tx leg-side]
  (some (fn [leg] (when (= leg-side (:side leg)) leg)) (:legs tx)))

(defn- leg
  "Returns the leg matching both side and balance-status, or nil — needed
  once a transaction carries several legs across balance buckets."
  [tx leg-side balance-status]
  (some (fn [l]
          (when (and (= leg-side (:side l))
                     (= balance-status (:balance-status l)))
            l))
        (:legs tx)))

(defn- balanced?
  "Σ debit == Σ credit over a transaction's postings."
  [tx]
  (let [legs (:legs tx)
        total (fn [s]
                (reduce + 0 (map :amount (filter #(= s (:side %)) legs))))]
    (= (total :leg-side-debit) (total :leg-side-credit))))

(defn- allow-all
  "Minimal policy fixture that allow-lists every payment action and
  permits a high daily count, so the leg-shape assertions don't get
  short-circuited by a capability denial or a limit breach."
  []
  [{:enabled true
    :capabilities
    [{:effect :effect-allow
      :kind {:internal-payment
             {:action :internal-payment-action-submit}}}
     {:effect :effect-allow
      :kind {:inbound-payment
             {:action :inbound-payment-action-receive}}}
     {:effect :effect-allow
      :kind {:outbound-payment
             {:action :outbound-payment-action-send}}}]
    :limits
    [{:kind {:internal-payment {}}
      :bound {:kind {:max {:aggregate {:kind {:count {:value 1000000
                                                      :window
                                                      :time-window-daily}}}}}}}
     {:kind {:inbound-payment {}}
      :bound {:kind {:max {:aggregate {:kind {:count {:value 1000000
                                                      :window
                                                      :time-window-daily}}}}}}}
     {:kind {:outbound-payment {}}
      :bound {:kind {:max {:aggregate
                           {:kind {:count {:value 1000000
                                           :window :time-window-daily}}}}}}}]}])

(defn- empty-aggregates
  "Aggregates fixture where today's count and value are both zero for
  the given payment kind — the shape `core/submit-*` builds before the
  domain checks. Combined with `allow-all`, leg-shape tests stay clear
  of any limit boundary."
  [kind]
  {kind {#{:bank-id :business-day} 0
         #{:bank-id :business-day :amount} 0}})

(defn- account
  "Minimal cash-account fixture — just the fields the domain guards
  read. Opened unless a status is given."
  ([account-id currency]
   (account account-id currency :cash-account-status-opened))
  ([account-id currency account-status]
   {:account-id account-id
    :currency currency
    :account-status account-status}))

(def ^:private non-opened-statuses
  [:cash-account-status-opening
   :cash-account-status-suspended
   :cash-account-status-closing
   :cash-account-status-closed])

(defn- guard-payload
  "The three payload keys `ensure-account-operable` promises."
  [anomaly]
  (select-keys (error/payload anomaly) [:account-id :status :allowed]))

(deftest internal-payment->transaction-test
  (let [tx (SUT/internal-payment->transaction {:idempotency-key "idem-1"
                                               :debtor-account-id "debtor"
                                               :creditor-account-id "creditor"
                                               :currency "GBP"
                                               :amount 500
                                               :reference "Test"}
                                              (account "debtor" "GBP")
                                              (account "creditor" "GBP")
                                              (allow-all)
                                              (empty-aggregates
                                               :internal-payment))]
    (testing "envelope carries idempotency-key, type, currency, reference"
      (is (= "idem-1" (:idempotency-key tx)))
      (is (= :transaction-type-internal-transfer (:transaction-type tx)))
      (is (= "GBP" (:currency tx)))
      (is (= "Test" (:reference tx))))
    (testing "two legs, both on :balance-type-default / :posted"
      (is (= 2 (count (:legs tx))))
      (is (every? (fn [leg]
                    (and (= :balance-type-default (:balance-type leg))
                         (= :balance-status-posted (:balance-status leg))))
                  (:legs tx))))
    (testing "debtor debited, creditor credited, both for `amount`"
      (is (= {:account-id "debtor" :amount 500}
             (select-keys (side tx :leg-side-debit) [:account-id :amount])))
      (is (= {:account-id "creditor" :amount 500}
             (select-keys (side tx :leg-side-credit) [:account-id :amount]))))))

(deftest inbound-payment->transaction-test
  (let [tx (SUT/inbound-payment->transaction {:scheme-transaction-id "stx-1"
                                              :currency "GBP"
                                              :amount 1000
                                              :reference "Invoice"}
                                             (account "creditor" "GBP")
                                             "internal"
                                             (allow-all)
                                             (empty-aggregates
                                              :inbound-payment))]
    (testing "scheme-transaction-id becomes the idempotency-key"
      (is (= "stx-1" (:idempotency-key tx)))
      (is (= :transaction-type-inbound-transfer (:transaction-type tx))))
    (testing
      "GL 1100 cash-at-correspondent debited, customer (default) credited"
      ;; Post-CoA: when the creditor is identified via BBAN match, inbound
      ;; settles direct to the bank's 1100 Cash at correspondent on
      ;; `:balance-type-default :balance-status-posted`.
      (let [debit (side tx :leg-side-debit)
            credit (side tx :leg-side-credit)]
        (is (= "internal" (:account-id debit)))
        (is (= :balance-type-default (:balance-type debit)))
        (is (= :balance-status-posted (:balance-status debit)))
        (is (= 1000 (:amount debit)))
        (is (= "creditor" (:account-id credit)))
        (is (= :balance-type-default (:balance-type credit)))
        (is (= :balance-status-posted (:balance-status credit)))
        (is (= 1000 (:amount credit)))))))

(deftest outbound-payment->transaction-test
  (let [tx (SUT/outbound-payment->transaction {:idempotency-key "ob-1"
                                               :debtor-account-id "debtor"
                                               :currency "GBP"
                                               :amount 250
                                               :reference "Outbound"}
                                              (account "debtor" "GBP")
                                              "internal"
                                              (allow-all)
                                              (empty-aggregates
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
    (testing "legs balance" (is (balanced? tx)))))

(deftest outbound-settlement->transaction-test
  (let [payment {:payment-id "pmt-1"
                 :debtor-account-id "debtor"
                 :currency "GBP"
                 :amount 250
                 :reference "Beer and nuts"}
        tx (SUT/outbound-settlement->transaction payment
                                                 (account "debtor" "GBP")
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
    (testing "legs balance" (is (balanced? tx)))))

(deftest outbound-reversal->transaction-test
  (let [payment {:payment-id "pmt-2"
                 :debtor-account-id "debtor"
                 :currency "GBP"
                 :amount 250}
        tx (SUT/outbound-reversal->transaction payment
                                               (account "debtor" "GBP")
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
    (testing "legs balance" (is (balanced? tx)))))

(deftest operable?-test
  (testing "opened alone is operable"
    (is (SUT/operable? (account "debtor" "GBP")))
    (doseq [status non-opened-statuses]
      (is (not (SUT/operable? (account "debtor" "GBP" status)))
          (str status " is not operable")))))

(deftest account-not-operable-test
  (testing "an opened debtor and creditor still build a transaction"
    (is (not (error/anomaly? (SUT/internal-payment->transaction
                              {:idempotency-key "idem-op"
                               :debtor-account-id "debtor"
                               :creditor-account-id "creditor"
                               :currency "GBP"
                               :amount 100}
                              (account "debtor" "GBP")
                              (account "creditor" "GBP")
                              (allow-all)
                              (empty-aggregates :internal-payment)))))
    (is (not (error/anomaly? (SUT/outbound-payment->transaction
                              {:idempotency-key "ob-op"
                               :debtor-account-id "debtor"
                               :currency "GBP"
                               :amount 100}
                              (account "debtor" "GBP")
                              "internal"
                              (allow-all)
                              (empty-aggregates :outbound-payment))))))
  (doseq [status non-opened-statuses]
    (testing (str "internal payment, debtor " (name status))
      (let [result (SUT/internal-payment->transaction
                    {:idempotency-key "idem-op"
                     :debtor-account-id "debtor"
                     :creditor-account-id "creditor"
                     :currency "GBP"
                     :amount 100}
                    (account "debtor" "GBP" status)
                    (account "creditor" "GBP")
                    (allow-all)
                    (empty-aggregates :internal-payment))]
        (is (error/anomaly? result))
        (is (= :payment/debtor-account-not-operable (error/kind result)))
        (is (= {:account-id "debtor"
                :status status
                :allowed #{:cash-account-status-opened}}
               (guard-payload result)))))
    (testing (str "internal payment, creditor " (name status))
      (let [result (SUT/internal-payment->transaction
                    {:idempotency-key "idem-op"
                     :debtor-account-id "debtor"
                     :creditor-account-id "creditor"
                     :currency "GBP"
                     :amount 100}
                    (account "debtor" "GBP")
                    (account "creditor" "GBP" status)
                    (allow-all)
                    (empty-aggregates :internal-payment))]
        (is (error/anomaly? result))
        (is (= :payment/creditor-account-not-operable (error/kind result)))
        (is (= {:account-id "creditor"
                :status status
                :allowed #{:cash-account-status-opened}}
               (guard-payload result)))))
    (testing (str "outbound payment, debtor " (name status))
      (let [result (SUT/outbound-payment->transaction
                    {:idempotency-key "ob-op"
                     :debtor-account-id "debtor"
                     :currency "GBP"
                     :amount 100}
                    (account "debtor" "GBP" status)
                    "internal"
                    (allow-all)
                    (empty-aggregates :outbound-payment))]
        (is (error/anomaly? result))
        (is (= :payment/debtor-account-not-operable (error/kind result)))
        (is (= {:account-id "debtor"
                :status status
                :allowed #{:cash-account-status-opened}}
               (guard-payload result)))))))

(deftest currency-mismatch-test
  (testing "internal-payment: debtor currency must match payment currency"
    (let [result (SUT/internal-payment->transaction
                  {:idempotency-key "idem-2"
                   :debtor-account-id "debtor"
                   :creditor-account-id "creditor"
                   :currency "EUR"
                   :amount 100}
                  (account "debtor" "GBP")
                  (account "creditor" "EUR")
                  (allow-all)
                  (empty-aggregates :internal-payment))]
      (is (error/anomaly? result))
      (is (= :payment/currency-mismatch (error/kind result)))))
  (testing "internal-payment: creditor currency must match payment currency"
    (let [result (SUT/internal-payment->transaction
                  {:idempotency-key "idem-3"
                   :debtor-account-id "debtor"
                   :creditor-account-id "creditor"
                   :currency "GBP"
                   :amount 100}
                  (account "debtor" "GBP")
                  (account "creditor" "EUR")
                  (allow-all)
                  (empty-aggregates :internal-payment))]
      (is (error/anomaly? result))
      (is (= :payment/currency-mismatch (error/kind result)))))
  (testing "inbound-payment: creditor currency must match payment currency"
    (let [result (SUT/inbound-payment->transaction
                  {:scheme-transaction-id "stx-2" :currency "USD" :amount 100}
                  (account "creditor" "GBP")
                  "internal"
                  (allow-all)
                  (empty-aggregates :inbound-payment))]
      (is (error/anomaly? result))
      (is (= :payment/currency-mismatch (error/kind result)))))
  (testing "outbound-payment: debtor currency must match payment currency"
    (let [result (SUT/outbound-payment->transaction {:idempotency-key "ob-2"
                                                     :debtor-account-id "debtor"
                                                     :currency "USD"
                                                     :amount 100}
                                                    (account "debtor" "GBP")
                                                    "internal"
                                                    (allow-all)
                                                    (empty-aggregates
                                                     :outbound-payment))]
      (is (error/anomaly? result))
      (is (= :payment/currency-mismatch (error/kind result))))))

(defn- inbound-count-capped
  [cap]
  [{:enabled true
    :capabilities [{:effect :effect-allow
                    :kind {:inbound-payment
                           {:action :inbound-payment-action-receive}}}]
    :limits [{:kind {:inbound-payment {}}
              :bound {:kind {:max {:aggregate
                                   {:kind {:count {:value cap
                                                   :window
                                                   :time-window-daily}}}}}}}]}])

(defn- inbound-count
  [n]
  {:inbound-payment {#{:bank-id :business-day} n}})

(deftest check-inbound-acceptance-test
  (let [data {:currency "GBP" :amount 100}
        creditor (account "creditor" "GBP")]
    (testing "an acceptable inbound passes"
      (is (nil? (SUT/check-inbound-acceptance data
                                              creditor
                                              (allow-all)
                                              (inbound-count 0)))))
    (testing "a currency the creditor account does not hold is refused"
      (let [result (SUT/check-inbound-acceptance {:currency "EUR" :amount 100}
                                                 creditor
                                                 (allow-all)
                                                 (inbound-count 0))]
        (is (SUT/refused? result))
        (is (= :payment/currency-mismatch (error/kind result)))))
    (testing "no receive capability is refused"
      (let [result
            (SUT/check-inbound-acceptance data creditor [] (inbound-count 0))]
        (is (SUT/refused? result))
        (is (= :policy/denied (error/kind result)))))
    (testing "an inbound past the daily count is refused"
      (let [result (SUT/check-inbound-acceptance data
                                                 creditor
                                                 (inbound-count-capped 2)
                                                 (inbound-count 2))]
        (is (SUT/refused? result))
        (is (= :policy/limit-exceeded (error/kind result)))))
    (testing "a failure is not a refusal"
      (is (not (SUT/refused? (error/fail :payment/settle-inbound
                                         {:message "Failed"})))))))

(deftest inbound-release->transaction-test
  (let [held {:payment-id "pmt-held"
              :bank-id "bank"
              :currency "GBP"
              :amount 700
              :business-day 20000}
        creditor (account "creditor" "GBP")]
    (testing "an accepted release credits the creditor from 1100"
      (let [tx (SUT/inbound-release->transaction held
                                                 creditor
                                                 "1100"
                                                 (allow-all)
                                                 (inbound-count 0))]
        (is (= "release-in-pmt-held" (:idempotency-key tx)))
        (is (= {:account-id "1100" :amount 700}
               (select-keys (side tx :leg-side-debit) [:account-id :amount])))
        (is (= {:account-id "creditor" :amount 700}
               (select-keys (side tx :leg-side-credit) [:account-id :amount])))
        (is (balanced? tx))))
    (testing "a release past the daily count is refused"
      (let [result (SUT/inbound-release->transaction held
                                                     creditor
                                                     "1100"
                                                     (inbound-count-capped 2)
                                                     (inbound-count 2))]
        (is (SUT/refused? result))
        (is (= :policy/limit-exceeded (error/kind result)))))
    (testing "a release without the receive capability is refused"
      (let [result (SUT/inbound-release->transaction held
                                                     creditor
                                                     "1100"
                                                     []
                                                     (inbound-count 0))]
        (is (SUT/refused? result))
        (is (= :policy/denied (error/kind result)))))))

(deftest release-count-test
  (let [held {:business-day 20000}]
    (testing "a release on the held day leaves the held record uncounted"
      (is (= 2 (SUT/release-count 3 held 20000))))
    (testing "a release on a later day counts every inbound of that day"
      (is (= 3 (SUT/release-count 3 held 20001))))))

(deftest suspended-from-held-test
  (let [held {:payment-id "pmt-held"
              :creditor-account-id "creditor"
              :scheme-transaction-id "held-placeholder"
              :payment-status :inbound-payment-status-held
              :updated-at 1700000000000}
        suspended (SUT/suspended-from-held held "stx-9" "txn-9")]
    (is (= :inbound-payment-status-suspended (:payment-status suspended)))
    (is (= "stx-9" (:scheme-transaction-id suspended)))
    (is (= "txn-9" (:transaction-id suspended)))
    (is (= "creditor" (:creditor-account-id suspended)))
    (is (>= (:updated-at suspended) (:updated-at held)))))

(deftest select-hold-to-return-test
  (let [hold-a {:payment-id "pmt-a"}
        hold-b {:payment-id "pmt-b"}]
    (testing "no open hold selects nothing"
      (is (nil? (SUT/select-hold-to-return [] "e2e-1"))))
    (testing "one open hold is selected"
      (is (= hold-a (SUT/select-hold-to-return [hold-a] "e2e-1"))))
    (testing "two open holds fail, naming the id and both candidates"
      (let [result (SUT/select-hold-to-return [hold-a hold-b] "e2e-1")]
        (is (error/error? result))
        (is (= :payment/ambiguous-hold (error/kind result)))
        (is (= {:end-to-end-id "e2e-1" :payment-ids ["pmt-a" "pmt-b"]}
               (select-keys (error/payload result)
                            [:end-to-end-id :payment-ids])))
        (is (string? (:message (error/payload result))))))))

(deftest settleable-outbound?-test
  (testing "pending and held settle"
    (is (SUT/settleable-outbound? {:payment-status
                                   :outbound-payment-status-pending}))
    (is (SUT/settleable-outbound? {:payment-status
                                   :outbound-payment-status-held})))
  (testing "completed and failed do not"
    (doseq [status [:outbound-payment-status-completed
                    :outbound-payment-status-failed]]
      (is (not (SUT/settleable-outbound? {:payment-status status}))
          (str status " is not settleable")))))

(deftest completed-outbound-payment-test
  (testing "flips :payment-status to completed"
    (let [pending {:payment-id "pmt-1"
                   :payment-status :outbound-payment-status-pending
                   :amount 250
                   :created-at 1700000000000
                   :updated-at 1700000000000}
          completed (SUT/completed-outbound-payment pending)]
      (is (= :outbound-payment-status-completed (:payment-status completed)))
      (testing "preserves other fields"
        (is (= "pmt-1" (:payment-id completed)))
        (is (= 250 (:amount completed))))
      (testing "bumps :updated-at past the original"
        (is (>= (:updated-at completed) (:updated-at pending)))))))

(def ^:private sweep-thresholds
  {:republish-after-ms 900000 :report-after-ms 86400000})

(defn- outbound
  [payment-id payment-status created-at]
  {:payment-id payment-id
   :bank-id "bnk.sweep"
   :payment-status payment-status
   :created-at created-at})

(deftest sweep-actions-test
  (let [created-at 1700000000000
        past-republish (+ created-at 900001)
        past-report (+ created-at 86400001)
        young-pending
        (outbound "pmt.young" :outbound-payment-status-pending past-republish)
        old-pending
        (outbound "pmt.pending" :outbound-payment-status-pending created-at)
        old-held (outbound "pmt.held" :outbound-payment-status-held created-at)
        old-completed
        (outbound "pmt.completed" :outbound-payment-status-completed created-at)
        old-failed
        (outbound "pmt.failed" :outbound-payment-status-failed created-at)
        payments [young-pending old-pending old-held old-completed old-failed]]
    (testing "past the republish threshold, an old pending republishes"
      (let [{:keys [republish report]}
            (SUT/sweep-actions payments past-republish sweep-thresholds)]
        (is (= ["pmt.pending"] (mapv :payment-id republish)))
        (is (= [] report))))
    (testing "past the report threshold, old pending and held report"
      (let [{:keys [republish report]}
            (SUT/sweep-actions payments past-report sweep-thresholds)]
        (is (= ["pmt.young" "pmt.pending"] (mapv :payment-id republish)))
        (is (= [{:payment-id "pmt.pending"
                 :bank-id "bnk.sweep"
                 :payment-status :outbound-payment-status-pending
                 :age-ms 86400001}
                {:payment-id "pmt.held"
                 :bank-id "bnk.sweep"
                 :payment-status :outbound-payment-status-held
                 :age-ms 86400001}]
               report))))
    (testing "a young pending payment gives nothing"
      (is (= {:republish [] :report []}
             (SUT/sweep-actions [young-pending]
                                past-republish
                                sweep-thresholds))))))

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
  (let [payment {:payment-id "pmt.1"
                 :payment-status :outbound-payment-status-pending}]
    (testing "the event's failure kind and reason code are recorded"
      (let [failed (SUT/failed-outbound-payment
                    payment
                    {:failure-kind :failure-kind-refused
                     :reason-code "AC01"
                     :cancellation-reason "HTTP 400"})]
        (is (= :outbound-payment-status-failed (:payment-status failed)))
        (is (= :outbound-payment-failure-kind-refused (:failure-kind failed)))
        (is (= "AC01" (:failure-reason-code failed)))
        (is (= "HTTP 400" (:failure-reason failed)))))
    (testing "an event that predates them is a decline coded NARR"
      (let [failed (SUT/failed-outbound-payment payment
                                                {:cancellation-code
                                                 "SCENARIO_REJECTED"})]
        (is (= :outbound-payment-failure-kind-declined (:failure-kind failed)))
        (is (= "NARR" (:failure-reason-code failed)))
        (is (not (contains? failed :failure-reason)))))))

(defn- posted-leg
  [account-id side amount & {:as extra}]
  (merge {:account-id account-id
          :balance-type :balance-type-default
          :balance-status :balance-status-posted
          :side side
          :amount amount}
         extra))

(def ^:private accounts
  {:cash-accounts {"acc.a" "acc.a"
                   "acc.b" "acc.b"
                   "acc.house" "acc.house"
                   "acc.no" "acc.house"}
   :cash-at-correspondent-id "gl.1100"
   :own-funds "acc.house"})

(defn- transfers
  ([legs] (transfers legs nil))
  ([legs scheme-account-id]
   (SUT/provider-transfers
    {:legs legs}
    (assoc accounts :scheme-account-id scheme-account-id))))

(deftest provider-transfers-test
  (testing "an internal payment moves between the two accounts"
    (is (= [{:debtor "acc.a" :creditor "acc.b" :amount 500}]
           (transfers
            [(posted-leg "acc.a" :leg-side-debit 500)
             (posted-leg "acc.b" :leg-side-credit 500)
             (posted-leg "gl.2100" :leg-side-debit 500 :control true)
             (posted-leg "gl.2100" :leg-side-credit 500 :control true)]))))
  (testing "interest capitalised is paid from the bank's own funds"
    (is (= [{:debtor "acc.house" :creditor "acc.a" :amount 7}]
           (transfers [(posted-leg "acc.a"
                                   :leg-side-debit 7
                                   :balance-type :balance-type-interest-accrued)
                       (posted-leg "acc.a" :leg-side-credit 7)]))))
  (testing "a reward from the house account"
    (is (= [{:debtor "acc.house" :creditor "acc.b" :amount 300}]
           (transfers [(posted-leg "acc.house" :leg-side-debit 300)
                       (posted-leg "acc.b" :leg-side-credit 300)]))))
  (testing "the scheme's own settlement moves nothing"
    (is (= []
           (transfers [(posted-leg "gl.1100" :leg-side-debit 900)
                       (posted-leg "acc.a" :leg-side-credit 900)]
                      "acc.a"))))
  (testing "an inbound parked in suspense moves to the bank's own funds"
    (is (= [{:debtor "acc.a" :creditor "acc.house" :amount 900}]
           (transfers [(posted-leg "gl.1100" :leg-side-debit 900)
                       (posted-leg "gl.2500" :leg-side-credit 900)]
                      "acc.a"))))
  (testing "money from outside the scheme is credited from outside"
    (is (= [{:debtor nil :creditor "acc.house" :amount 5000}]
           (transfers
            [(posted-leg "gl.1100" :leg-side-debit 5000)
             (posted-leg "acc.house" :leg-side-credit 5000)
             (posted-leg "gl.3100" :leg-side-credit 5000 :control true)]))))
  (testing "money leaving to 1100 without the scheme stays with own funds"
    (is (= [{:debtor "acc.a" :creditor "acc.house" :amount 50}]
           (transfers [(posted-leg "acc.a" :leg-side-debit 50)
                       (posted-leg "gl.1100" :leg-side-credit 50)]))))
  (testing "an account the provider holds nothing for is held in own funds"
    (is (= [{:debtor "acc.a" :creditor "acc.house" :amount 20}]
           (transfers [(posted-leg "acc.a" :leg-side-debit 20)
                       (posted-leg "acc.no" :leg-side-credit 20)]))))
  (testing "pending legs move nothing"
    (is (= []
           (transfers
            [(posted-leg "acc.a"
                         :leg-side-debit 20
                         :balance-status :balance-status-pending-outgoing)
             (posted-leg "gl.1200"
                         :leg-side-credit 20
                         :balance-status :balance-status-pending-outgoing)])))))

(deftest mirror-party-test
  (testing "an account with a provider account holds its own money"
    (is (= "acc.a"
           (SUT/mirror-party {:account-id "acc.a" :provider-account-id "A1"}
                             "acc.house"))))
  (testing "as does one the provider is opening"
    (is (= "acc.a"
           (SUT/mirror-party {:account-id "acc.a"
                              :account-status :cash-account-status-opening}
                             "acc.house"))))
  (testing "one the provider holds nothing for is held in own funds"
    (is (= "acc.house"
           (SUT/mirror-party {:account-id "acc.a"
                              :account-status :cash-account-status-opened}
                             "acc.house")))))

(deftest transfer-outcome-test
  (let [pending {:transfer-id "ptr.1"
                 :status :provider-transfer-status-pending}]
    (is (= :provider-transfer-status-failed
           (:status (SUT/transfer-outcome pending
                                          :provider-transfer-status-failed
                                          "Insufficient funds"))))
    (is (nil? (SUT/transfer-outcome
               (assoc pending :status :provider-transfer-status-completed)
               :provider-transfer-status-failed
               nil)))))
