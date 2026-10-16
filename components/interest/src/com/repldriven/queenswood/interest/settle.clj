(ns com.repldriven.queenswood.interest.settle
  (:require
    [com.repldriven.queenswood.interest.domain.chart :as chart]
    [com.repldriven.queenswood.interest.domain.settlement :as settlement]
    [com.repldriven.queenswood.balance-query.interface :as balance-query]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as transactions]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(def ^:private platform
  "The actor of what the interest brick records on its own, the
  settlement a close makes among them."
  {:kind :actor-kind-platform :principal-id "interest"})

(defn- expense-id
  "The bank's interest expense account in `currency`, read only where
  `closure-interest` forfeits, since a closure that pays posts nothing
  against it."
  [txn bank-id closure-interest currency]
  (let [{:keys [forfeit forfeit-days]} (:kind closure-interest)]
    (when (or forfeit forfeit-days)
      (let-nom> [chart-of-accounts (ledger-accounts/list-accounts txn bank-id)
                 accounts (chart/accrual-accounts chart-of-accounts
                                                  bank-id
                                                  currency)]
        (:expense accounts)))))

(defn- record
  "Records `transaction`, after checking its controls, returning its
  recorded legs."
  [txn bank-id transaction]
  (let-nom>
    [_ (ledger-accounts/accept-legs txn
                                    bank-id
                                    (:currency transaction)
                                    (:legs transaction))
     recorded (transactions/record-transactions
               txn
               [(assoc transaction :actor platform)])]
    (into [] (mapcat :legs) recorded)))

(defn settle-accrued
  [txn data]
  (let [{:keys [account interest business-day policies]} data
        {:keys [bank-id account-id product-id version-id currency]} account]
    (let-nom>
      [version (products/get-version txn bank-id product-id version-id)
       account-balances (balance-query/list-balances txn bank-id account-id)
       expense (expense-id txn bank-id interest currency)
       {:keys [payment forfeiture] :as settled}
       (settlement/settle bank-id
                          account
                          version
                          interest
                          account-balances
                          expense
                          business-day)
       paid-legs (when payment (record txn bank-id payment))
       _ (when paid-legs
           (balances/accept-legs txn
                                 bank-id
                                 paid-legs
                                 :transaction-type-interest-capitalization
                                 {:policies (policy/platform-policies
                                             policies)}))
       _ (when forfeiture (record txn bank-id forfeiture))]
      {:paid (:paid settled 0) :forfeited (:forfeited settled 0)})))
