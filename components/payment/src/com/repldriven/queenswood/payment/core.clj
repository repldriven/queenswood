(ns com.repldriven.queenswood.payment.core
  (:require
    [com.repldriven.queenswood.payment.domain.checks :as checks]
    [com.repldriven.queenswood.payment.domain.internal :as internal]
    [com.repldriven.queenswood.payment.domain.outbound :as outbound]
    [com.repldriven.queenswood.payment.provider :as provider]
    [com.repldriven.queenswood.payment.store :as store]

    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as
     ledger-accounts]
    [com.repldriven.queenswood.payment-query.interface :as q]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as
     transactions]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error
     :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.message-bus.interface :as message-bus]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- or-already-submitted
  "On a uniqueness violation — a redelivered submit-payment command
  carrying an already-seen idempotency-key — read the existing payment
  back via `find-fn` and return it, so the caller gets the original
  resource instead of a bare rejection. Any other value passes through
  unchanged."
  [txn data result find-fn]
  (if (and (store/uniqueness-violation? result)
           (:idempotency-key data))
    (let-nom> [existing (find-fn txn
                                 (:bank-id data)
                                 (:idempotency-key data))]
      (or existing result))
    result))

(defn submit-internal
  [config data]
  (or-already-submitted
   config
   data
   (store/transact
    config
    (fn [txn]
      (let [{:keys [bank-id debtor-account-id
                    creditor-account-id currency]}
            data
            business-day (checks/current-business-day
                          (utility/now)
                          (:business-day-cutoff config))
            policies (policy/get-effective-policies
                      txn
                      {:bank-id bank-id})]
        (let-nom>
          [debtor-account (cash-accounts/get-account
                           txn
                           bank-id
                           debtor-account-id)
           creditor-account (cash-accounts/get-account
                             txn
                             bank-id
                             creditor-account-id)
           today-count (q/count-internal-by-org-business-day
                        txn
                        bank-id
                        business-day)
           aggregates {:internal-payment
                       {#{:bank-id :business-day} today-count}}
           payment-transaction (internal/internal-payment->transaction
                                data
                                debtor-account
                                creditor-account
                                policies
                                aggregates)
           expanded-legs (ledger-accounts/add-control-legs
                          txn
                          bank-id
                          currency
                          (:legs payment-transaction))
           transaction (transactions/record-transaction
                        txn
                        (assoc payment-transaction :legs expanded-legs))
           {:keys [transaction-id transaction-type legs]} transaction
           _ (balances/apply-legs txn bank-id legs transaction-type)
           payment (internal/new-internal-payment data
                                                  business-day
                                                  transaction-id)
           _ (store/save-internal-payment txn payment)]
          payment))))
   q/find-internal-payment-by-idempotency-key))

(defn- publish-scheme-command
  [config payment debtor-account]
  (let [{:keys [bus schemas]} config
        {:keys [payment-id bank-id creditor-bban creditor-name
                currency amount reference scheme]}
        payment
        channel (provider/payment-command-channel config config bank-id)
        {:keys [bban provider-account-id]} debtor-account
        schema (get schemas "submit-payment")]
    (when (and bus schema channel)
      (let [result (let-nom>
                     [channel channel
                      payload (avro/serialize schema
                                              {:payment-id payment-id
                                               :end-to-end-id payment-id
                                               :debtor-bban bban
                                               :debtor-provider-account-id
                                               provider-account-id
                                               :creditor-bban creditor-bban
                                               :creditor-name creditor-name
                                               :amount amount
                                               :currency currency
                                               :reference reference
                                               :scheme scheme})]
                     (message-bus/send bus
                                       channel
                                       {:command "submit-payment"
                                        :id (str (utility/uuidv7))
                                        :correlation-id (str (utility/uuidv7))
                                        :causation-id payment-id
                                        :traceparent
                                        (telemetry/inject-traceparent)
                                        :payload payload}))]
        (when (error/anomaly? result)
          (log/error "Failed to publish submit-payment"
                     {:payment-id payment-id :anomaly result}))
        result))))

(defn republish-pending
  [config payment]
  (let [{:keys [payment-id bank-id debtor-account-id]} payment
        debtor-account (cash-accounts/get-account config
                                                  bank-id
                                                  debtor-account-id)]
    (if (error/anomaly? debtor-account)
      (do (log/error "Failed to read the debtor account to republish"
                     {:payment-id payment-id :anomaly debtor-account})
          debtor-account)
      (publish-scheme-command config payment debtor-account))))

(defn submit-outbound
  [config data]
  (let [{:keys [bank-id debtor-account-id currency]} data
        raw (store/transact
             config
             (fn [txn]
               (let [business-day (checks/current-business-day
                                   (utility/now)
                                   (:business-day-cutoff config))
                     policies (policy/get-effective-policies
                               txn
                               {:bank-id bank-id})]
                 (let-nom>
                   [declaration (provider/declaration config txn bank-id)
                    _ (outbound/check-scheme (:scheme data) declaration)
                    debtor-account (cash-accounts/get-account
                                    txn
                                    bank-id
                                    debtor-account-id)
                    pending-outbound
                    (ledger-accounts/find-by-code
                     txn
                     bank-id
                     :gl-account-code-pending-outbound
                     currency)
                    today-count (q/count-outbound-by-org-business-day
                                 txn
                                 bank-id
                                 business-day)
                    today-sum (q/sum-outbound-by-org-business-day
                               txn
                               bank-id
                               business-day)
                    aggregates {:outbound-payment
                                {#{:bank-id :business-day}
                                 today-count
                                 #{:bank-id :business-day :amount}
                                 today-sum}}
                    transaction (outbound/outbound-payment->transaction
                                 data
                                 debtor-account
                                 (:ledger-account-id pending-outbound)
                                 policies
                                 aggregates)
                    expanded-legs (ledger-accounts/add-control-legs
                                   txn
                                   bank-id
                                   currency
                                   (:legs transaction))
                    transaction+legs (transactions/record-transaction
                                      txn
                                      (assoc transaction
                                             :legs
                                             expanded-legs))
                    {:keys [transaction-id transaction-type legs]}
                    transaction+legs
                    _ (balances/apply-legs txn bank-id legs transaction-type)
                    payment (outbound/new-outbound-payment data
                                                           business-day
                                                           transaction-id)
                    _ (store/save-outbound-payment
                       txn
                       payment
                       {:change-kind :outbound-payment-change-kind-submit})]
                   {:payment payment :debtor-account debtor-account}))))]
    (if (store/uniqueness-violation? raw)
      (let-nom> [existing (or-already-submitted
                           config
                           data
                           raw
                           q/find-outbound-payment-by-idempotency-key)]
        (when (outbound/republishable-outbound? existing)
          (republish-pending config existing))
        existing)
      (let-nom> [{:keys [payment debtor-account]} raw]
        (publish-scheme-command config payment debtor-account)
        payment))))
