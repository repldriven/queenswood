(ns com.repldriven.queenswood.api.simulate.handlers
  (:require
    [com.repldriven.queenswood.api.auth :as auth]
    [com.repldriven.queenswood.api.commands :as commands]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]

    [com.repldriven.mono.error.interface :as error]))

(defn- dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [transactions]} dispatchers]
    transactions))

(defn- interest-dispatcher
  [request]
  (let [{:keys [dispatchers]} request
        {:keys [interest]} dispatchers]
    interest))

(defn- check-bank
  "Confirms the path's `{bank-id}` resolves to a real bank.
  Returns nil when found, or the anomaly-response for `:bank/not-found`."
  [request]
  (let [{:keys [record-db record-store parameters]} request
        {:keys [path]} parameters
        {:keys [bank-id]} path
        result (banks/get-bank
                {:record-db record-db :record-store record-store}
                bank-id)]
    (when (error/anomaly? result)
      (errors/anomaly->response result))))

(defn- check-principal-bank
  "Confirms the caller may act on the path's `{bank-id}`. A service
  principal carries the bank its token was minted for; an admin carries
  none and takes its bank from the path. Returns nil when the caller
  may act, or the 403 the `authorize` interceptor would have returned.
  Runs before `check-bank`, so a foreign bank id is refused whether or
  not that bank exists and the answer tells the caller nothing about
  another tenant."
  [request]
  (let [{:keys [auth parameters]} request
        {:keys [path]} parameters
        {:keys [bank-id]} path]
    (when-not (or (= bank-id (:bank-id auth))
                  (contains? (:roles auth) :admin))
      (auth/forbidden-response
       "Token is not this bank's; simulate only your own bank"))))

(defn inbound-transfer
  [request]
  (or
   (check-principal-bank request)
   (check-bank request)
   (let [{:keys [record-db record-store parameters]} request
         {:keys [path body]} parameters
         {:keys [bank-id]} path
         {:keys [account-id amount currency]} body
         ;; A simulated inbound is money arriving from another bank for
         ;; a known account, so it lands in the bank's 1100 cash-at-
         ;; correspondent (asset up) and credits the target account.
         ;; Suspense (2500) is reserved for genuinely unmatched inbounds.
         cash (ledger-accounts/find-by-code
               {:record-db record-db :record-store record-store}
               bank-id
               :gl-account-code-cash-at-correspondent)]
     (if (or (nil? cash) (error/anomaly? cash))
       (errors/anomaly->response
        (error/fail
         :simulate/no-cash-at-correspondent-account
         {:message
          "Bank has no 1100 cash-at-correspondent account in its chart"
          :bank-id bank-id}))
       (let [txn {:record-db record-db :record-store record-store}
             account (cash-accounts/get-account txn bank-id account-id)
             product-type (when (and (map? account)
                                     (not (error/anomaly? account)))
                            (:product-type account))
             legs [{:account-id (:ledger-account-id cash)
                    :balance-type :balance-type-default
                    :balance-status :balance-status-posted
                    :side :leg-side-debit
                    :amount amount}
                   (cond-> {:account-id account-id
                            :balance-type :balance-type-default
                            :balance-status :balance-status-posted
                            :side :leg-side-credit
                            :amount amount}
                           product-type
                           (assoc :product-type product-type))]
             expanded-legs (ledger-accounts/add-control-legs
                            txn
                            bank-id
                            legs)]
         (if (error/anomaly? expanded-legs)
           (errors/anomaly->response expanded-legs)
           (commands/send
            (dispatcher request)
            request
            "record-transaction"
            "transaction"
            {:bank-id bank-id
             :transaction-type :transaction-type-inbound-transfer
             :currency currency
             :reference "Simulated inbound transfer"
             :legs expanded-legs})))))))

(defn accrue
  [request]
  (or (check-bank request)
      (let [{:keys [parameters]} request
            {:keys [path body]} parameters
            {:keys [bank-id]} path
            {:keys [as-of-date]} body]
        (commands/send
         (interest-dispatcher request)
         request
         "accrue-day-interest"
         "interest-result"
         {:bank-id bank-id
          :as-of-date as-of-date}))))

(defn capitalize
  [request]
  (or (check-bank request)
      (let [{:keys [parameters]} request
            {:keys [path body]} parameters
            {:keys [bank-id]} path
            {:keys [as-of-date]} body]
        (commands/send
         (interest-dispatcher request)
         request
         "capitalize-accrued-interest"
         "interest-result"
         {:bank-id bank-id
          :as-of-date as-of-date}))))
