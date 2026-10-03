(ns com.repldriven.queenswood.interest.domain.capitalization
  (:require
    [com.repldriven.queenswood.interest.domain.balances :as balances]

    [com.repldriven.mono.utility.interface :as utility]))

(defn- idempotency-key
  [account-id as-of-date]
  (str "capitalize-" account-id "-" as-of-date))

(defn- transaction
  "One account's capitalisation: DR interest payable, CR the customer's
  default balance, for the whole accrued amount — what the bank owed
  becomes the customer's to spend. The customer's default balance is
  part of the deposit control its product type rolls into, so this one
  entry moves both sides of the bank's books."
  [bank-id account-id currency payable-id accrued as-of-date]
  {:bank-id bank-id
   :idempotency-key (idempotency-key account-id as-of-date)
   :transaction-type :transaction-type-interest-capital
   :currency currency
   :reference (str "Monthly interest capitalization "
                   (utility/epoch-day->iso-date as-of-date))
   :legs [{:account-id payable-id
           :balance-type :balance-type-default
           :balance-status :balance-status-posted
           :side :leg-side-debit
           :amount accrued
           :currency currency}
          {:account-id account-id
           :balance-type :balance-type-default
           :balance-status :balance-status-posted
           :side :leg-side-credit
           :amount accrued
           :currency currency}]})

(defn- accrued-leg
  "The debit that empties the customer's accrued interest balance as it
  is paid. Applied with the transaction's legs and not recorded among
  them, as accrual raises that balance without a transaction."
  [account-id currency accrued]
  {:account-id account-id
   :balance-type :balance-type-interest-accrued
   :balance-status :balance-status-posted
   :side :leg-side-debit
   :amount accrued
   :currency currency})

(defn sweep
  "What one account capitalises: the `:transaction` to record, the
  `:legs` to apply — its legs and the debit of the accrued balance —
  and the `:amount` swept beside the `:principal` it came off. A sweep
  takes whatever is there, so those two are the same number.

  Nil when nothing has accrued, which is not a failure — most accounts
  on most days have nothing to sweep.

  This is the only part of interest a customer sees. Accrual runs
  silently day by day, capitalisation is the statement line."
  [bank-id account-id currency payable-id account-balances as-of-date]
  (let [accrued (balances/accrued-amount account-balances currency)]
    (when-not (zero? accrued)
      (let [tx (transaction bank-id
                            account-id
                            currency
                            payable-id
                            accrued
                            as-of-date)]
        {:transaction tx
         :legs (conj (:legs tx) (accrued-leg account-id currency accrued))
         :amount accrued
         :principal accrued}))))
