(ns com.repldriven.queenswood.api.simulate.handlers
  (:require
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]

    [com.repldriven.mono.error.interface :as error]))

(defn- dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [transactions]} dispatchers]
    transactions))

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
                          "transaction"
                          {:bank-id bank-id
                           :transaction-type
                           :transaction-type-inbound-transfer
                           :currency currency
                           :reference "Simulated inbound transfer"
                           :legs checked-legs})]
            ;; The caller no longer chooses the account, so the answer
            ;; names the one credited.
            (cond-> response
                    (= 200 (:status response))
                    (update :body assoc :account-id (:account-id house)))))))))
