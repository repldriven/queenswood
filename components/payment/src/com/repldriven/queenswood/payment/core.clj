(ns com.repldriven.queenswood.payment.core
  (:require
    [com.repldriven.queenswood.payment.domain.checks :as checks]
    [com.repldriven.queenswood.payment.domain.internal :as internal]
    [com.repldriven.queenswood.payment.domain.outbound :as outbound]
    [com.repldriven.queenswood.payment.provider :as provider]
    [com.repldriven.queenswood.payment.store :as store]

    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.bank-activity.interface :as bank-activity]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as
     ledger-accounts]
    [com.repldriven.queenswood.payment-query.interface :as q]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as
     transactions]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
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
            policies (telemetry/with-span
                      ["payment-policies"]
                      (policy/get-effective-policies-cached txn
                                                            {:bank-id bank-id}
                                                            (:policy-cache
                                                             config)))]
        (let-nom>
          [[debtor-account creditor-account]
           (telemetry/with-span ["payment-accounts"]
                                (cash-accounts/get-accounts-by-id
                                 txn
                                 bank-id
                                 [debtor-account-id creditor-account-id]))
           today-count (telemetry/with-span
                        ["payment-daily-count"]
                        (q/count-internal-by-org-business-day txn
                                                              bank-id
                                                              business-day))
           aggregates {:internal-payment
                       {#{:bank-id :business-day} today-count}}
           payment-transaction (telemetry/with-span
                                ["payment-checks"]
                                (internal/internal-payment->transaction
                                 data
                                 debtor-account
                                 creditor-account
                                 policies
                                 aggregates))
           checked-legs (telemetry/with-span ["payment-controls"]
                                             (ledger-accounts/ensure-controls
                                              txn
                                              bank-id
                                              currency
                                              (:legs payment-transaction)))
           transaction (telemetry/with-span
                        ["payment-record-transaction"]
                        (transactions/record-transaction
                         txn
                         (assoc payment-transaction :legs checked-legs)))
           {:keys [transaction-id transaction-type legs]} transaction
           _ (telemetry/with-span
              ["payment-apply-legs"]
              (balances/apply-legs txn
                                   bank-id
                                   legs
                                   transaction-type
                                   {:policies (policy/platform-policies
                                               policies)}))
           payment (internal/new-internal-payment data
                                                  business-day
                                                  transaction-id)
           _ (telemetry/with-span ["payment-save"]
                                  (store/save-internal-payment txn payment))]
          payment)))
    :payment/submit-internal
    "Failed to submit internal payment")
   q/find-internal-payment-by-idempotency-key))

(defn- record-submitted
  [txn payment debtor-account]
  (let [{:keys [payment-id bank-id debtor-account-id creditor-bban
                creditor-name currency amount reference scheme]}
        payment
        {:keys [bban provider-account-id]} debtor-account]
    (bank-activity/record txn
                          {:bank-id bank-id
                           :event-name "outbound-payment-submitted"
                           :data (utility/assoc-some
                                  {:payment-id payment-id
                                   :debtor-account-id debtor-account-id
                                   :debtor-bban bban
                                   :creditor-bban creditor-bban
                                   :creditor-name creditor-name
                                   :amount amount
                                   :currency currency}
                                  :debtor-provider-account-id
                                  provider-account-id
                                  :reference
                                  reference
                                  :scheme
                                  scheme)
                           :causation-id payment-id
                           :dedup-key payment-id})))

(defn submit-outbound
  [config data]
  (let [{:keys [bank-id debtor-account-id currency]} data
        raw (store/transact
             config
             (fn [txn]
               (let [business-day (checks/current-business-day
                                   (utility/now)
                                   (:business-day-cutoff config))
                     policies (telemetry/with-span
                               ["payment-policies"]
                               (policy/get-effective-policies-cached
                                txn
                                {:bank-id bank-id}
                                (:policy-cache config)))]
                 (let-nom>
                   [declaration (telemetry/with-span
                                 ["payment-declaration"]
                                 (provider/declaration config txn bank-id))
                    _ (outbound/check-scheme (:scheme data) declaration)
                    debtor-account (telemetry/with-span
                                    ["payment-debtor-account"]
                                    (cash-accounts/get-account
                                     txn
                                     bank-id
                                     debtor-account-id))
                    pending-outbound
                    (telemetry/with-span ["payment-pending-outbound"]
                                         (ledger-accounts/find-by-code
                                          txn
                                          bank-id
                                          :gl-account-code-pending-outbound
                                          currency))
                    today (telemetry/with-span
                           ["payment-daily-totals"]
                           (q/outbound-totals-by-org-business-day
                            txn
                            bank-id
                            business-day))
                    aggregates {:outbound-payment
                                {#{:bank-id :business-day}
                                 (:count today)
                                 #{:bank-id :business-day :amount}
                                 (:sum today)}}
                    transaction (telemetry/with-span
                                 ["payment-checks"]
                                 (outbound/outbound-payment->transaction
                                  data
                                  debtor-account
                                  (:ledger-account-id pending-outbound)
                                  policies
                                  aggregates))
                    checked-legs (telemetry/with-span
                                  ["payment-controls"]
                                  (ledger-accounts/ensure-controls
                                   txn
                                   bank-id
                                   currency
                                   (:legs transaction)))
                    transaction+legs (telemetry/with-span
                                      ["payment-record-transaction"]
                                      (transactions/record-transaction
                                       txn
                                       (assoc transaction
                                              :legs
                                              checked-legs)))
                    {:keys [transaction-id transaction-type legs]}
                    transaction+legs
                    stored (telemetry/with-span ["payment-stored-legs"]
                                                (ledger-accounts/stored-legs
                                                 txn
                                                 bank-id
                                                 currency
                                                 legs))
                    _ (telemetry/with-span
                       ["payment-apply-legs"]
                       (balances/apply-legs txn
                                            bank-id
                                            stored
                                            transaction-type
                                            {:policies (policy/platform-policies
                                                        policies)}))
                    payment (outbound/new-outbound-payment data
                                                           business-day
                                                           transaction-id)
                    _ (telemetry/with-span
                       ["payment-save"]
                       (store/save-outbound-payment
                        txn
                        payment
                        {:change-kind :outbound-payment-change-kind-submit}))
                    _ (telemetry/with-span
                       ["payment-record-activity"]
                       (record-submitted txn payment debtor-account))]
                   {:payment payment :debtor-account debtor-account})))
             :payment/submit-outbound
             "Failed to submit outbound payment")]
    (if (store/uniqueness-violation? raw)
      (or-already-submitted config
                            data
                            raw
                            q/find-outbound-payment-by-idempotency-key)
      (let-nom> [{:keys [payment]} raw]
        payment))))
