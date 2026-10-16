(ns com.repldriven.queenswood.interest.domain.settlement
  (:require
    [com.repldriven.queenswood.interest.domain.accrual :as accrual]
    [com.repldriven.queenswood.interest.domain.balances :as balances]

    [com.repldriven.queenswood.calc.interface :as calc]

    [com.repldriven.mono.utility.interface :as utility]))

(defn forfeited
  "What `closure-interest` forfeits of `accrued`: none under `pay`, all
  of it under `forfeit`, and under `forfeit-days` that many days'
  interest on `principal` at `day-rates`, never more than `accrued`."
  [closure-interest accrued day-rates principal]
  (let [{:keys [forfeit forfeit-days]} (:kind closure-interest)]
    (cond forfeit
          accrued

          (and forfeit-days day-rates)
          (min accrued
               (calc/days-interest day-rates principal (:days forfeit-days)))

          :else
          0)))

(defn- leg
  [account-id product-type balance-type side amount currency]
  {:account-id account-id
   :product-type product-type
   :balance-type balance-type
   :balance-status :balance-status-posted
   :side side
   :amount amount
   :currency currency})

(defn- transaction
  [bank-id account kind transaction-type reference legs business-day]
  {:bank-id bank-id
   :idempotency-key
   (str "settle-" (:account-id account) "-" business-day "-" kind)
   :transaction-type transaction-type
   :currency (:currency account)
   :reference (str reference " " (utility/epoch-day->iso-date business-day))
   :legs legs})

(defn- payment
  "The paid part's transaction: from the account's interest-accrued
  bucket to its default one, as a capitalisation moves it."
  [bank-id account amount business-day]
  (let [{:keys [account-id product-type currency]} account]
    (transaction bank-id
                 account
                 "pay"
                 :transaction-type-interest-capitalization
                 "Interest paid at close"
                 [(leg account-id
                       product-type
                       :balance-type-interest-accrued
                       :leg-side-debit
                       amount
                       currency)
                  (leg account-id
                       product-type
                       :balance-type-default
                       :leg-side-credit
                       amount
                       currency)]
                 business-day)))

(defn- forfeiture
  "The forfeited part's transaction: out of the account's
  interest-accrued bucket against interest expense, `expense-id`,
  undoing what its accrual debited."
  [bank-id account expense-id amount business-day]
  (let [{:keys [account-id product-type currency]} account]
    (transaction bank-id
                 account
                 "forfeit"
                 :transaction-type-interest-forfeiture
                 "Interest forfeited at close"
                 [(leg account-id
                       product-type
                       :balance-type-interest-accrued
                       :leg-side-debit
                       amount
                       currency)
                  (leg expense-id
                       :account-product-type-general-ledger
                       :balance-type-default
                       :leg-side-credit
                       amount
                       currency)]
                 business-day)))

(defn settle
  "How a closure on `business-day` settles `account`'s accrued interest
  by `closure-interest`, at its `version`'s rates, from its
  `account-balances`: the `:paid` and `:forfeited` amounts, and the
  `:payment` and `:forfeiture` transactions recording each, where it is
  not zero, the forfeiture against `expense-id`. Nil where nothing has
  accrued."
  [bank-id account version closure-interest account-balances expense-id
   business-day]
  (let [accrued (balances/accrued-amount account-balances)]
    (when (pos? accrued)
      (let [day-rates (accrual/day-rates (get-in version
                                                 [:interest-terms :accrual])
                                         (:version-from-on account)
                                         business-day)
            principal (balances/principal-amount account-balances
                                                 (:currency account))
            lost (forfeited closure-interest accrued day-rates principal)
            paid (- accrued lost)]
        (cond-> {:paid paid :forfeited lost}
                (pos? paid)
                (assoc :payment (payment bank-id account paid business-day))

                (pos? lost)
                (assoc
                 :forfeiture
                 (forfeiture bank-id account expense-id lost business-day)))))))
