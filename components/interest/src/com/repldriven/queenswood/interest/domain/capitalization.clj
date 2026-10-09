(ns com.repldriven.queenswood.interest.domain.capitalization
  (:require
    [com.repldriven.queenswood.interest.domain.balances :as balances]

    [com.repldriven.mono.utility.interface :as utility]))

(defn- idempotency-key
  [account-id as-of-date]
  (str "capitalize-" account-id "-" as-of-date))

(defn- leg
  [account balance-type side amount currency]
  {:account-id (:account-id account)
   :product-type (:product-type account)
   :balance-type balance-type
   :balance-status :balance-status-posted
   :side side
   :amount amount
   :currency currency})

(defn- transaction
  "One account's capitalisation: what accrued leaves its interest-accrued
  bucket for its default one, a debit and a credit, or the reverse where
  an overdrawn principal accrued a charge. Each bucket rolls into its
  control, 2400 and the deposit control of the account's product type,
  so the one entry moves both sides of the bank's books."
  [bank-id account currency accrued as-of-date]
  (let [[from to] (if (neg? accrued)
                    [:leg-side-credit :leg-side-debit]
                    [:leg-side-debit :leg-side-credit])
        amount (abs accrued)]
    {:bank-id bank-id
     :idempotency-key (idempotency-key (:account-id account) as-of-date)
     :transaction-type :transaction-type-interest-capitalization
     :currency currency
     :reference (str "Monthly interest capitalization "
                     (utility/epoch-day->iso-date as-of-date))
     :legs [(leg account :balance-type-interest-accrued from amount currency)
            (leg account :balance-type-default to amount currency)]}))

(defn sweep
  "What one account capitalises: the `:transaction` to record and the
  `:amount` swept beside the `:principal` it came off. A sweep takes
  whatever is there, so those two are the same number.

  Nil when nothing has accrued, which is not a failure — most accounts
  on most days have nothing to sweep.

  Accrual moves the interest-accrued bucket day by day; this is the
  line on the account's spendable balance."
  [bank-id account currency account-balances as-of-date]
  (let [accrued (balances/accrued-amount account-balances)]
    (when-not (zero? accrued)
      {:transaction (transaction bank-id account currency accrued as-of-date)
       :amount accrued
       :principal accrued})))
