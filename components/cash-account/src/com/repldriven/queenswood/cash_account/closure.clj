(ns com.repldriven.queenswood.cash-account.closure
  (:require
    [com.repldriven.queenswood.cash-account.domain :as domain]

    [com.repldriven.queenswood.balance-query.interface :as balances-q]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.cash-account-query.interface :as q]
    [com.repldriven.queenswood.interest.interface :as interest]
    [com.repldriven.queenswood.ledger-account.interface :as
     ledger-accounts]
    [com.repldriven.queenswood.payment.interface :as payment]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- charge-fee
  "Charges closing `account` `amount` against 4100 fee income, where it
  is more than zero."
  [txn account amount business-day policies actor]
  (when (pos? amount)
    (let [{:keys [bank-id currency]} account]
      (let-nom>
        [income (ledger-accounts/find-by-code txn
                                              bank-id
                                              :ledger-account-code-fee-income
                                              currency)
         transaction (domain/fee-transaction account
                                             (:ledger-account-id income)
                                             amount
                                             business-day)
         checked-legs (ledger-accounts/accept-legs txn
                                                   bank-id
                                                   currency
                                                   (:legs transaction))
         recorded (transactions/record-transaction
                   txn
                   (assoc transaction :legs checked-legs :actor actor))]
        (balances/accept-legs txn
                              bank-id
                              (:legs recorded)
                              :transaction-type-fee
                              {:policies (policy/platform-policies
                                          policies)})))))

(defn close
  "Closes `account` by the command in `data` under `policies`, returning
  it `closing`: settles its accrued interest by its version's closure
  terms, charges an early closure's fee, and moves what is left to the
  payout account `data` names. A closing account closed again is charged
  no fee."
  [txn data account policies]
  (let [{:keys [payout-account-id actor]} data
        {:keys [bank-id account-id product-id version-id]} account
        today (utility/today)]
    (let-nom>
      [account-balances (balances-q/list-balances txn bank-id account-id)
       payout (when payout-account-id
                (q/find-account txn bank-id payout-account-id))
       closing (domain/close-account account
                                     account-balances
                                     payout-account-id
                                     payout
                                     actor
                                     policies)
       version (products/get-version txn bank-id product-id version-id)
       term (domain/closure-term account (:closure-terms version) today)
       {:keys [paid]} (interest/settle-accrued txn
                                               {:account account
                                                :interest (:interest term)
                                                :business-day today
                                                :policies policies})
       settled (+ (domain/default-held account-balances) paid)
       fee (if (= :cash-account-status-closing (:status account))
             0
             (domain/closure-fee (:fee term) settled))
       _ (charge-fee txn account fee today policies actor)
       held (- settled fee)
       _ (domain/check-emptied account held payout policies)
       _ (when (and payout (pos? held))
           (payment/pay-out-closure txn
                                    {:account account
                                     :payout-account payout
                                     :amount held
                                     :business-day today
                                     :policies policies
                                     :actor actor}))]
      closing)))
