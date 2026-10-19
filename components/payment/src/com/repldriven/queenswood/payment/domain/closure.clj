(ns com.repldriven.queenswood.payment.domain.closure
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

(def platform
  "The actor of what the payment brick posts as it carries out a close."
  {:kind :actor-kind-platform :principal-id "payment"})

(defn close-requested?
  "Whether the operation change `data` reports is a close operation just
  recorded, which is all a closure answers."
  [data]
  (and (= :cash-account-operation-kind-close (:kind data))
       (nil? (:status-before data))
       (= :cash-account-operation-status-pending (:status-after data))))

(defn default-held
  "What `balances` hold in the posted default bucket, credits less
  debits."
  [balances]
  (transduce (comp (filter (fn [b]
                             (and (= :balance-type-default (:balance-type b))
                                  (= :balance-status-posted
                                     (:balance-status b)))))
                   (map (fn [b] (- (:credit b 0) (:debit b 0)))))
             +
             0
             balances))

(def ^:private basis-points 10000)

(defn closure-fee
  "What `fee` charges a closure leaving `held` in the default bucket: a
  `flat` amount, or `balance`'s `rate-bps` of `held`, rounded down, raised
  to its `min` and capped at its `max`. Never more than `held`, and zero
  where there is no fee or nothing is held."
  [fee held]
  (let [{:keys [flat balance]} (:kind fee)
        charged (cond flat
                      (:amount flat)

                      balance
                      (let [{lowest :min highest :max} balance]
                        (cond-> (quot (* held (:rate-bps balance))
                                      basis-points)
                                lowest
                                (max lowest)

                                highest
                                (min highest)))

                      :else
                      0)]
    (max 0 (min charged held))))

(defn check-held
  "Refuses a close leaving `account` overdrawn, `held` below zero once its
  interest is settled and its fee charged."
  [account held]
  (when (neg? held)
    (error/reject :payment/overdrawn-on-close
                  {:message "The account is overdrawn and cannot be closed"
                   :account-id (:account-id account)
                   :held held})))

(defn- leg
  [account side amount]
  {:account-id (:account-id account)
   :product-type (:product-type account)
   :balance-type :balance-type-default
   :balance-status :balance-status-posted
   :side side
   :amount amount
   :currency (:currency account)})

(defn fee-transaction
  "The `fee` transaction the close `operation-id` charges closing
  `account`, `amount` from its default bucket to the fee income account
  `fee-income-id`."
  [account operation-id fee-income-id amount business-day]
  (let [{:keys [bank-id currency]} account]
    {:bank-id bank-id
     :idempotency-key (str "closure-fee-" operation-id)
     :transaction-type :transaction-type-fee
     :currency currency
     :reference (str "Closure fee " (utility/epoch-day->iso-date business-day))
     :legs [(leg account :leg-side-debit amount)
            {:account-id fee-income-id
             :product-type :account-product-type-general-ledger
             :balance-type :balance-type-default
             :balance-status :balance-status-posted
             :side :leg-side-credit
             :amount amount
             :currency currency}]}))

(defn closure-transaction
  "The `closure` transaction the close `operation-id` moves `amount` in,
  from closing `account`'s default bucket to `nominated`'s."
  [account operation-id nominated amount business-day]
  (let [{:keys [bank-id currency]} account]
    {:bank-id bank-id
     :idempotency-key (str "closure-" operation-id)
     :transaction-type :transaction-type-closure
     :currency currency
     :reference (str "Closure "
                     (utility/epoch-day->iso-date business-day))
     :legs [(leg account :leg-side-debit amount)
            (leg nominated :leg-side-credit amount)]}))

(defn- closure
  [operation status]
  (let [{:keys [bank-id account-id operation-id nominated-account-id]}
        operation]
    (utility/assoc-some {:bank-id bank-id
                         :closure-id (utility/generate-id "acl")
                         :status status
                         :account-id account-id
                         :operation-id operation-id
                         :created-at (utility/now)
                         :created-by platform}
                        :nominated-account-id
                        nominated-account-id)))

(defn paid-closure
  "The closure of the close `operation` asked for, in `currency`, having
  paid `interest-paid`, charged `fee-charged` and moved `amount` in
  `transaction`."
  [operation currency interest-paid fee-charged amount transaction]
  (utility/assoc-some (assoc (closure operation
                                      :account-closure-status-paid)
                             :currency currency
                             :interest-paid interest-paid
                             :fee-charged fee-charged
                             :amount amount)
                      :transaction-id
                      (:transaction-id transaction)))

(defn refused-closure
  "The closure of the close `operation` asked for, in `currency`, refused
  by `rejection`."
  [operation currency rejection]
  (assoc (closure operation :account-closure-status-refused)
         :currency currency
         :refused-reason (:message (error/payload rejection))))
