(ns com.repldriven.queenswood.payment.domain.payout
  (:require
    [com.repldriven.mono.utility.interface :as utility]))

(defn- leg
  [account side amount]
  {:account-id (:account-id account)
   :product-type (:product-type account)
   :balance-type :balance-type-default
   :balance-status :balance-status-posted
   :side side
   :amount amount
   :currency (:currency account)})

(defn closure-transaction
  "The `closure` transaction moving `amount` from closing `account`'s
  default bucket to `payout-account`'s."
  [account payout-account amount business-day]
  (let [{:keys [bank-id account-id currency]} account]
    {:bank-id bank-id
     :idempotency-key (str "closure-" account-id "-" (utility/uuidv7))
     :transaction-type :transaction-type-closure
     :currency currency
     :reference (str "Closure payout "
                     (utility/epoch-day->iso-date business-day))
     :legs [(leg account :leg-side-debit amount)
            (leg payout-account :leg-side-credit amount)]}))
