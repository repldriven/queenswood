(ns com.repldriven.queenswood.test-scenarios.quiescence
  (:require
    [com.repldriven.queenswood.balance-query.interface :as balance]
    [com.repldriven.queenswood.party-query.interface :as party]
    [com.repldriven.queenswood.payment-query.interface :as payment]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private default-deadline-ms 5000)
(def ^:private poll-interval-ms 25)

(defn wait-for-party-active
  ([bank bank-id party-id]
   (wait-for-party-active bank bank-id party-id default-deadline-ms))
  ([bank bank-id party-id deadline-ms]
   (let [deadline (+ (utility/now) deadline-ms)]
     (loop []
       (let [party (party/get-party bank bank-id party-id)
             status (when-not (error/anomaly? party) (:status party))]
         (cond
          (= :party-status-active status)
          :quiescent

          (>= (utility/now) deadline)
          (error/fail :scenario/quiescence-timeout
                      {:message "Party did not become active"
                       :bank-id bank-id
                       :party-id party-id
                       :status status})

          :else
          (do (Thread/sleep poll-interval-ms) (recur))))))))

(defn wait-for-outbound-completed
  "Poll the OutboundPayment record until its `:payment-status` is
  `:outbound-payment-status-completed`. submit-outbound returns
  immediately after debiting the debtor and publishing the scheme
  command; ClearBank settles asynchronously and the bank-payment
  event-processor flips the status (Debit event → settle-outbound).
  The verb that just submitted needs to block until that hop lands
  so the surrounding model-eq check sees a consistent state."
  ([bank payment-id]
   (wait-for-outbound-completed bank payment-id default-deadline-ms))
  ([bank payment-id deadline-ms]
   (let [deadline (+ (utility/now) deadline-ms)]
     (loop []
       (let [pmt (payment/get-outbound-payment bank payment-id)
             status (when-not (error/anomaly? pmt) (:payment-status pmt))]
         (cond
          (= :outbound-payment-status-completed status)
          :quiescent

          (>= (utility/now) deadline)
          (error/fail :scenario/quiescence-timeout
                      {:message "Outbound payment did not complete"
                       :payment-id payment-id
                       :status status})

          :else
          (do (Thread/sleep poll-interval-ms) (recur))))))))

(defn- net-balance
  [bank bank-real-id account-id currency]
  (let [b (balance/get-balance bank
                               bank-real-id
                               account-id
                               :balance-type-default
                               currency
                               :balance-status-posted)]
    (when-not (error/anomaly? b)
      (- (:credit b 0) (:debit b 0)))))

(defn wait-for-credit
  "Poll the default-posted balance of `account-id` until its net
  (credit − debit) is at least `target`. Use after an outbound
  payment with an internal creditor — ClearBank fires the Debit
  (settle-outbound, marks the OutboundPayment :completed) and
  Credit (settle-inbound, credits the creditor) events as two
  separate transaction-settled webhooks, so
  `wait-for-outbound-completed` only catches the first hop."
  ([bank bank-real-id account-id currency target]
   (wait-for-credit bank
                    bank-real-id
                    account-id
                    currency
                    target
                    default-deadline-ms))
  ([bank bank-real-id account-id currency target deadline-ms]
   (let [deadline (+ (utility/now) deadline-ms)]
     (loop []
       (let [net (net-balance bank bank-real-id account-id currency)]
         (cond
          (and net (>= net target))
          :quiescent

          (>= (utility/now) deadline)
          (error/fail :scenario/quiescence-timeout
                      {:message "Creditor balance did not reach target"
                       :account-id account-id
                       :target target
                       :actual net})

          :else
          (do (Thread/sleep poll-interval-ms) (recur))))))))

(defn wait-for-count
  "Poll `count-fn` until it returns an anomaly or at least `target`, or
  `deadline-ms` passes. Returns the last value `count-fn` returned, so the
  caller asserts on what was seen rather than on a timeout."
  [count-fn target deadline-ms]
  (let [deadline (+ (utility/now) deadline-ms)]
    (loop []
      (let [n (count-fn)]
        (if (or (error/anomaly? n) (>= n target) (>= (utility/now) deadline))
          n
          (do (Thread/sleep poll-interval-ms) (recur)))))))

(defn wait
  [_bank]
  :quiescent)
