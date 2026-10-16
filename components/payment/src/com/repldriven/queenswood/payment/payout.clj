(ns com.repldriven.queenswood.payment.payout
  (:require
    [com.repldriven.queenswood.payment.account-limits :as account-limits]
    [com.repldriven.queenswood.payment.domain.checks :as checks]
    [com.repldriven.queenswood.payment.domain.payout :as payout]

    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.ledger-account.interface :as
     ledger-accounts]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as
     transactions]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(defn pay-out-closure
  [txn data]
  (let [{:keys [account payout-account amount business-day policies actor]}
        data
        {:keys [bank-id currency]} account
        payout-id (:account-id payout-account)]
    (let-nom>
      [limits (account-limits/get-aggregates nil
                                             txn
                                             policies
                                             payout-account
                                             :payment-direction-in
                                             business-day)
       _ (checks/check-account-payment (:policies limits)
                                       payout-id
                                       :payment-direction-in
                                       amount
                                       currency
                                       (:aggregates limits))
       transaction (payout/closure-transaction account
                                               payout-account
                                               amount
                                               business-day)
       checked-legs (ledger-accounts/accept-legs txn
                                                 bank-id
                                                 currency
                                                 (:legs transaction))
       recorded (transactions/record-transaction
                 txn
                 (assoc transaction :legs checked-legs :actor actor))
       _ (balances/accept-legs
          txn
          bank-id
          (:legs recorded)
          :transaction-type-closure
          {:policies (policy/platform-policies policies)
           :account-policies (account-limits/version-policies
                              {payout-id limits})})]
      recorded)))
