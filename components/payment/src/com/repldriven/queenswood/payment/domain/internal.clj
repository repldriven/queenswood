(ns com.repldriven.queenswood.payment.domain.internal
  (:require
    [com.repldriven.queenswood.payment.domain.checks :as checks]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- ensure-distinct-accounts
  [debtor-account-id creditor-account-id]
  (when (= debtor-account-id creditor-account-id)
    (error/reject :payment/self-transfer-not-permitted
                  {:message "Debtor and creditor accounts must differ"
                   :account-id debtor-account-id})))

(defn internal-payment->transaction
  [data debtor-account creditor-account policies aggregates]
  (let [{:keys [bank-id idempotency-key debtor-account-id
                creditor-account-id currency amount
                reference]}
        data]
    (let-nom>
      [_ (checks/ensure-account-operable debtor-account :debtor)
       _ (checks/ensure-account-operable creditor-account :creditor)
       _ (ensure-distinct-accounts debtor-account-id creditor-account-id)
       _ (checks/ensure-currency-matches currency debtor-account)
       _ (checks/ensure-currency-matches currency creditor-account)
       _ (checks/check-capability policies
                                  :internal-payment
                                  :internal-payment-action-submit)
       _ (checks/check-daily-count policies :internal-payment aggregates)]
      (utility/assoc-some
       {:bank-id bank-id
        :idempotency-key idempotency-key
        :transaction-type :transaction-type-internal-transfer
        :currency currency
        :legs [{:account-id debtor-account-id
                :product-type (:product-type debtor-account)
                :balance-type :balance-type-default
                :balance-status :balance-status-posted
                :side :leg-side-debit
                :amount amount}
               {:account-id creditor-account-id
                :product-type (:product-type creditor-account)
                :balance-type :balance-type-default
                :balance-status :balance-status-posted
                :side :leg-side-credit
                :amount amount}]}
       :reference
       reference))))

(defn new-internal-payment
  [data business-day transaction-id]
  (let [{:keys [idempotency-key bank-id debtor-account-id
                creditor-account-id currency amount
                reference]}
        data
        now (utility/now)]
    (utility/assoc-some
     {:payment-id (utility/generate-id "pmt")
      :idempotency-key idempotency-key
      :bank-id bank-id
      :business-day business-day
      :debtor-account-id debtor-account-id
      :creditor-account-id creditor-account-id
      :currency currency
      :amount amount
      :transaction-id transaction-id
      :created-at now
      :updated-at now}
     :reference
     reference)))
