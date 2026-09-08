(ns com.repldriven.queenswood.interest.domain.capitalization
  (:require
    [com.repldriven.queenswood.interest.domain.balances :as balances]

    [com.repldriven.mono.utility.interface :as utility]))

(defn- idempotency-key
  [account-id as-of-date]
  (str "capitalize-" account-id "-" as-of-date))

(defn- transaction
  "One account's capitalisation: DR its accrued interest balance, CR its
  default balance, for the whole accrued amount. The accrued leg rolls
  up into interest payable, the default leg into the deposit control
  its product type feeds, so what the bank owed becomes the customer's
  to spend."
  [bank-id account-id currency accrued as-of-date]
  {:bank-id bank-id
   :idempotency-key (idempotency-key account-id as-of-date)
   :transaction-type :transaction-type-interest-capital
   :currency currency
   :reference (str "Monthly interest capitalization "
                   (utility/epoch-day->iso-date as-of-date))
   :legs [{:account-id account-id
           :balance-type :balance-type-interest-accrued
           :balance-status :balance-status-posted
           :side :leg-side-debit
           :amount accrued}
          {:account-id account-id
           :balance-type :balance-type-default
           :balance-status :balance-status-posted
           :side :leg-side-credit
           :amount accrued}]})

(defn sweep
  "What one account capitalises: the `:transaction` to post, and the
  `:amount` swept beside the `:principal` it came off. A sweep takes
  whatever is there, so those two are the same number.

  Nil when nothing has accrued, which is not a failure — most accounts
  on most days have nothing to sweep.

  This is the only part of interest a customer sees. Accrual runs
  silently day by day, capitalisation is the statement line."
  [bank-id account-id currency account-balances as-of-date]
  (let [accrued (balances/accrued-amount account-balances currency)]
    (when-not (zero? accrued)
      {:transaction (transaction bank-id
                                 account-id
                                 currency
                                 accrued
                                 as-of-date)
       :amount accrued
       :principal accrued})))

(defn entries
  "The ledger entries a capitalisation run owes, given the currency and
  product type of every account it saw. One per currency and product
  type: the credit side is whichever deposit control that product rolls
  into, so a single entry per currency could not name them all and
  still balance."
  [currency+product-types]
  currency+product-types)

(defn ledger-transaction
  "The bank's side of one group of a capitalisation run: DR the
  interest-payable control for what the group swept out of its accrued
  balances, CR the deposit control that product type rolls into. Nil
  when the group swept nothing.

  One entry per group rather than per currency, because a single entry
  could not name every deposit control and still balance. Both legs
  name ledger accounts, so neither rolls up into a control of its own.
  The key is the group's identity, so a repeat posts once."
  [gl bank-id currency product-type total as-of-date]
  (when-not (zero? total)
    {:bank-id bank-id
     :idempotency-key (str "capitalize-run-" bank-id
                           "-" as-of-date
                           "-"
                           currency
                           "-" (name product-type))
     :transaction-type :transaction-type-interest-capital
     :currency currency
     :reference (str "Monthly interest capitalization "
                     (utility/epoch-day->iso-date as-of-date))
     :legs [{:account-id (:payable gl)
             :balance-type :balance-type-default
             :balance-status :balance-status-posted
             :side :leg-side-debit
             :amount total
             :currency currency}
            {:account-id (get-in gl [:controls product-type])
             :balance-type :balance-type-default
             :balance-status :balance-status-posted
             :side :leg-side-credit
             :amount total
             :currency currency}]}))
