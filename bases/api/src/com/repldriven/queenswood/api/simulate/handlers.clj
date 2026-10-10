(ns com.repldriven.queenswood.api.simulate.handlers
  (:require
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.errors :as errors]
    [com.repldriven.queenswood.api.shared.actor :as actor]

    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.error.interface :as error]))

(defn- dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [transactions]} dispatchers]
    transactions))

(def ^:private leg-keys
  [:leg-id :transaction-id :account-id :balance-type :balance-status :side
   :amount :currency])

(defn- read-transfer
  "The posted transaction, with its legs on `account-ids` and the account
  it credited, as a response."
  [txn bank-id transaction-id account-ids credited]
  (let [result (transactions/get-transaction txn
                                             bank-id
                                             transaction-id
                                             account-ids)]
    (cond (error/anomaly? result)
          (errors/anomaly->response result)

          (nil? result)
          (errors/anomaly->response
           (error/fail :simulate/transaction-missing
                       {:message "The posted transaction could not be read"
                        :transaction-id transaction-id}))

          :else
          {:status 200
           :body (-> result
                     (select-keys [:transaction-id :transaction-type :currency
                                   :reference :created-at :created-by])
                     (assoc :account-id credited
                            :legs (mapv (fn [leg] (select-keys leg leg-keys))
                                        (:legs result))))})))

(defn inbound-transfer
  [request]
  (let [{:keys [auth record-db record-store parameters]} request
        {:keys [body]} parameters
        {:keys [bank-id]} auth
        {:keys [amount currency]} body
        ;; The bank's own money arriving from outside: 1100 cash-at-
        ;; correspondent up, the house account for the currency credited,
        ;; which rolls up into the 3100 own-funds control. A customer is
        ;; paid from there by an internal payment, as a reward is.
        cash (ledger-accounts/find-by-code
              {:record-db record-db :record-store record-store}
              bank-id
              :ledger-account-code-cash-at-correspondent
              currency)]
    ;; The lookup's own rejection travels: a bank with no 1100 in the
    ;; body's currency answers `:gl/missing-currency-account` 409
    ;; rather than a generic simulate failure.
    (if (error/anomaly? cash)
      (errors/anomaly->response cash)
      (let [txn {:record-db record-db :record-store record-store}
            house (cash-accounts/house-account txn bank-id currency)
            legs [{:account-id (:ledger-account-id cash)
                   :balance-type :balance-type-default
                   :balance-status :balance-status-posted
                   :side :leg-side-debit
                   :amount amount}
                  {:account-id (:account-id house)
                   :balance-type :balance-type-default
                   :balance-status :balance-status-posted
                   :side :leg-side-credit
                   :amount amount
                   :product-type (:product-type house)}]
            checked-legs (if (error/anomaly? house)
                           house
                           (ledger-accounts/ensure-controls txn
                                                            bank-id
                                                            currency
                                                            legs))]
        (if (error/anomaly? checked-legs)
          (errors/anomaly->response checked-legs)
          (let [response (commands/send
                          (dispatcher request)
                          request
                          "record-transaction"
                          "transaction-command-reply"
                          {:bank-id bank-id
                           :transaction-type
                           :transaction-type-inbound-transfer
                           :currency currency
                           :reference "Simulated inbound transfer"
                           :legs checked-legs
                           :actor (actor/actor auth)})]
            ;; The caller no longer chooses the account, so the answer
            ;; names the one credited.
            (commands/read-back response
                                (fn [{:keys [transaction-id]}]
                                  (read-transfer txn
                                                 bank-id
                                                 transaction-id
                                                 (mapv :account-id checked-legs)
                                                 (:account-id house))))))))))
