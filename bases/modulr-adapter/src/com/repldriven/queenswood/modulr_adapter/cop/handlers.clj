(ns com.repldriven.queenswood.modulr-adapter.cop.handlers
  (:require
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.circuit-breaker.interface :as circuit-breaker]
    [com.repldriven.queenswood.modulr-relay.interface :as relay]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.log.interface :as log]))

(def ^:private unavailable
  {:match-result :match-result-unavailable
   :reason-code "ACNS"
   :reason "Confirmation of Payee unavailable"})

(defn- result
  [{:keys [code name]}]
  (case code
    ("MATCHED" "PERSONAL_ACCOUNT_NAME_MATCHED" "BUSINESS_ACCOUNT_NAME_MATCHED")
    {:match-result :match-result-match}

    ("CLOSE_MATCH"
     "PERSONAL_ACCOUNT_CLOSE_MATCH"
     "BUSINESS_ACCOUNT_CLOSE_MATCH")
    {:match-result :match-result-close-match
     :actual-name name
     :reason-code "PANM"
     :reason "Partial name match"}

    "NOT_MATCHED"
    {:match-result :match-result-no-match
     :reason-code "ANNM"
     :reason "Account name does not match"}

    "ACCOUNT_DOES_NOT_EXIST"
    {:match-result :match-result-no-match
     :reason-code "AC01"
     :reason "Account does not exist"}

    (assoc unavailable
           :reason
           (str "Confirmation of Payee unavailable: " code))))

(defn- payer
  "The provider account the check is made from: the named account's, or
  else the bank's own funds'."
  [request bank-id account-id]
  (let [txn (select-keys request [:record-db :record-store])
        account (if account-id
                  (cash-accounts/get-account txn bank-id account-id)
                  (cash-accounts/house-account txn bank-id "GBP"))]
    (when-not (error/anomaly? account) (:provider-account-id account))))

(defn- outcome
  "Whether Modulr answered, a refusal included."
  [res]
  (if (= :retry (first (relay/classify res))) :failed :answered))

(defn outbound-cop
  [request]
  (let [{:keys [parameters]} request
        {:keys [creditor-name account account-type bank-id account-id]}
        (:body parameters)
        {:keys [sort-code account-number]} account
        payment-account-id (when bank-id (payer request bank-id account-id))]
    (log/info "Outbound CoP check" {:creditor-name creditor-name})
    (if (nil? payment-account-id)
      {:status 200 :body unavailable}
      (let [res (circuit-breaker/guard
                 (select-keys request [:record-db :record-store])
                 (get-in request [:delivery-policy :breaker])
                 "adapter:modulr"
                 outcome
                 (fn []
                   (relay/request
                    (select-keys request [:modulr-url :credentials])
                    {:method :post
                     :path "/account-name-check"
                     :body {:paymentAccountId payment-account-id
                            :sortCode sort-code
                            :accountNumber account-number
                            :accountType (if (= :account-type-business
                                                account-type)
                                           "BUSINESS"
                                           "PERSONAL")
                            :name creditor-name}})))
            [outcome body] (relay/classify res)]
        {:status 200
         :body (if (= :ok outcome) (result (:result body)) unavailable)}))))
