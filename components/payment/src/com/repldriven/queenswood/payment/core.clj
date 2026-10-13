(ns com.repldriven.queenswood.payment.core
  (:require
    [com.repldriven.queenswood.payment.account-limits :as account-limits]
    [com.repldriven.queenswood.payment.domain.checks :as checks]
    [com.repldriven.queenswood.payment.domain.inbound :as inbound]
    [com.repldriven.queenswood.payment.domain.internal :as internal]
    [com.repldriven.queenswood.payment.domain.outbound :as outbound]
    [com.repldriven.queenswood.payment.domain.scheme :as scheme]
    [com.repldriven.queenswood.payment.provider :as provider]
    [com.repldriven.queenswood.payment.store :as store]

    [com.repldriven.queenswood.audit.interface :as audit]
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

(defn- submitted
  "The audit entry of a payment the command in `data` submitted."
  [kind subject-kind payment data]
  {:bank-id (:bank-id payment)
   :kind kind
   :subject-kind subject-kind
   :subject-id (:payment-id payment)
   :request-id (:request-id data)
   :after payment
   :performed-by (:actor data)})

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

(defn- get-internal-aggregates
  [txn bank-id business-day]
  (let-nom> [today (telemetry/with-span ["payment-internal-aggregates"]
                                        (q/get-internal-aggregates
                                         txn
                                         bank-id
                                         business-day))]
    {:internal-payment {#{:bank-id :business-day} (:count today)}}))

(defn- get-outbound-aggregates
  [txn bank-id business-day]
  (let-nom> [today (telemetry/with-span ["payment-outbound-aggregates"]
                                        (q/get-outbound-aggregates
                                         txn
                                         bank-id
                                         business-day))]
    {:outbound-payment {#{:bank-id :business-day} (:count today)
                        #{:bank-id :business-day :amount} (:sum today)}}))

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
           _ (ledger-accounts/prefetch txn
                                       bank-id
                                       currency
                                       [(:product-type debtor-account)
                                        (:product-type creditor-account)])
           aggregates (get-internal-aggregates txn bank-id business-day)
           debtor-limits (account-limits/get-aggregates config
                                                        txn
                                                        policies
                                                        debtor-account
                                                        :payment-direction-out
                                                        business-day)
           creditor-limits (account-limits/get-aggregates
                            config
                            txn
                            policies
                            creditor-account
                            :payment-direction-in
                            business-day)
           payment-transaction (telemetry/with-span
                                ["payment-checks"]
                                (internal/internal-payment->transaction
                                 data
                                 debtor-account
                                 creditor-account
                                 policies
                                 (merge-with merge
                                             aggregates
                                             (:aggregates debtor-limits)
                                             (:aggregates creditor-limits))
                                 (assoc {}
                                        debtor-account-id
                                        (:policies debtor-limits)
                                        creditor-account-id
                                        (:policies creditor-limits))))
           checked-legs (telemetry/with-span ["payment-controls"]
                                             (ledger-accounts/accept-legs
                                              txn
                                              bank-id
                                              currency
                                              (:legs payment-transaction)))
           transaction (telemetry/with-span
                        ["payment-record-transaction"]
                        (transactions/record-transaction
                         txn
                         (assoc payment-transaction
                                :legs checked-legs
                                :actor (:actor data))))
           {:keys [transaction-id transaction-type legs]} transaction
           _ (telemetry/with-span
              ["payment-internal-accept-legs"]
              (balances/accept-legs
               txn
               bank-id
               legs
               transaction-type
               {:policies (policy/platform-policies policies)
                :account-policies (account-limits/version-policies
                                   (assoc {}
                                          debtor-account-id
                                          debtor-limits
                                          creditor-account-id
                                          creditor-limits))}))
           payment (internal/new-internal-payment data
                                                  business-day
                                                  transaction-id)
           _ (telemetry/with-span ["payment-save"]
                                  (store/save-internal-payment txn payment))
           _ (audit/record txn
                           (submitted
                            :audit-kind-internal-payment-submitted
                            :audit-subject-kind-internal-payment
                            payment
                            data))]
          payment)))
    :payment/submit-internal
    "Failed to submit internal payment")
   q/find-internal-payment-by-idempotency-key))

(defn- record-submitted
  [txn payment debtor-account]
  (let [{:keys [payment-id bank-id debtor-account-id creditor-bban
                creditor-name currency amount reference scheme-type]}
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
                                  (scheme/scheme-name scheme-type))
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
                    _ (outbound/check-scheme (scheme/scheme-name (:scheme-type
                                                                  data))
                                             declaration)
                    debtor-account (telemetry/with-span
                                    ["payment-debtor-account"]
                                    (cash-accounts/get-account
                                     txn
                                     bank-id
                                     debtor-account-id))
                    _ (ledger-accounts/prefetch
                       txn
                       bank-id
                       currency
                       []
                       [:ledger-account-code-pending-outbound])
                    pending-outbound
                    (telemetry/with-span ["payment-pending-outbound"]
                                         (ledger-accounts/find-by-code
                                          txn
                                          bank-id
                                          :ledger-account-code-pending-outbound
                                          currency))
                    aggregates (get-outbound-aggregates txn
                                                        bank-id
                                                        business-day)
                    limits (account-limits/get-aggregates
                            config
                            txn
                            policies
                            debtor-account
                            :payment-direction-out
                            business-day)
                    transaction (telemetry/with-span
                                 ["payment-checks"]
                                 (outbound/outbound-payment->transaction
                                  data
                                  debtor-account
                                  (:ledger-account-id pending-outbound)
                                  (:policies limits)
                                  (merge aggregates (:aggregates limits))))
                    checked-legs (telemetry/with-span
                                  ["payment-controls"]
                                  (ledger-accounts/accept-legs
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
                                              checked-legs
                                              :actor
                                              (:actor data))))
                    {:keys [transaction-id transaction-type legs]}
                    transaction+legs
                    _ (telemetry/with-span
                       ["payment-outbound-accept-legs"]
                       (balances/accept-legs
                        txn
                        bank-id
                        legs
                        transaction-type
                        {:policies (policy/platform-policies policies)
                         :account-policies (account-limits/version-policies
                                            {debtor-account-id limits})}))
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
                       (record-submitted txn payment debtor-account))
                    _ (audit/record
                       txn
                       (submitted
                        :audit-kind-outbound-payment-submitted
                        :audit-subject-kind-outbound-payment
                        payment
                        data))]
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

(defn simulate-inbound
  [config data]
  (let [{:keys [bank-id currency]} data]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [_ (ledger-accounts/find-by-code
             txn
             bank-id
             :ledger-account-code-cash-at-correspondent
             currency)
          account (cash-accounts/house-account txn bank-id currency)
          simulated (inbound/simulated-inbound data account)
          {:keys [account-id]} account
          _ (bank-activity/record txn
                                  {:bank-id bank-id
                                   :event-name
                                   "inbound-payment-simulation-requested"
                                   :data simulated
                                   :causation-id account-id
                                   :dedup-key (:simulation-id simulated)})
          _ (audit/record
             txn
             {:bank-id bank-id
              :kind :audit-kind-cash-account-inbound-payment-simulated
              :subject-kind :audit-subject-kind-cash-account
              :subject-id account-id
              :request-id (:request-id data)
              :before account
              :after account
              :performed-by (:actor data)})]
         (select-keys simulated [:account-id :amount :currency :reference])))
     :payment/simulate-inbound
     "Failed to ask for a simulated inbound payment")))
