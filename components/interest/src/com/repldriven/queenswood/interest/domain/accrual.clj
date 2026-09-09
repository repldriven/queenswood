(ns com.repldriven.queenswood.interest.domain.accrual
  (:require
    [com.repldriven.queenswood.interest.domain.balances :as balances]

    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private default-micro-scale 1000000)
(def ^:private default-day-count 365)

(defn- day-interest
  "A day's interest on `principal` at `interest-rate-bps`, opening with
  the sub-unit remainder `opening-carry`"
  ([principal opening-carry interest-rate-bps]
   (day-interest principal opening-carry interest-rate-bps nil))

  ([principal opening-carry interest-rate-bps opts]
   (let [{:keys [micro-scale day-count]
          :or {micro-scale default-micro-scale
               day-count default-day-count}}
         opts]
     (when-not (zero? interest-rate-bps)
       (let [total-micro (+ (* principal
                               interest-rate-bps
                               (quot micro-scale 10000))
                            (* opening-carry day-count))
             daily-micro (quot total-micro day-count)]
         {:amount (quot daily-micro micro-scale)
          :closing-carry (rem daily-micro micro-scale)})))))

(defn accrue
  "A day's accrual for one account: the `:principal` and
  `:opening-carry` it was computed from, the `:amount` earned and the
  `:closing-carry` left over, and the `:balance` those two advance.

  Returns nil when there is nothing to accrue, either:
  * the product pays no interest, or
  * it pays interest but there's no accrued interest balance to hold it."
  [account-id currency account-balances interest-rate-bps]
  (let [principal (balances/principal-amount account-balances currency)
        opening-carry (balances/carry-amount account-balances currency)
        accrued (day-interest principal opening-carry interest-rate-bps)]
    (when accrued
      (if-let [balance (balances/accrued-interest-balance
                        account-balances
                        currency)]
        (assoc accrued
               :balance balance
               :principal principal
               :opening-carry opening-carry)
        (do (log/warnf (str "Account %s has a non-zero interest rate but no"
                            " accrual balance - interest is not being accrued"
                            " for this account.")
                       account-id)
            nil)))))

(defn entries
  "The ledger entries an accrual run owes, given the currency and
  product type of every account it saw. One per currency: accrual debits
  interest expense and credits interest payable whatever product type
  an account is, so product type is not a distinction it needs."
  [currency+product-types]
  (into #{} (map first) currency+product-types))

(defn ledger-transaction
  "The bank's side of an accrual run, one entry per currency: DR
  interest expense, CR the interest-payable control, for everything the
  run accrued. Nil when the currency accrued nothing.

  Both legs name ledger accounts, so neither rolls up into a control of
  its own. The key is the run's identity, so a repeat posts once."
  [gl bank-id currency total as-of-date]
  (when-not (zero? total)
    {:bank-id bank-id
     :idempotency-key (str "accrue-run-" bank-id "-" as-of-date "-" currency)
     :transaction-type :transaction-type-interest-accrual
     :currency currency
     :reference (str "Daily interest accrual "
                     (utility/epoch-day->iso-date as-of-date))
     :legs [{:account-id (:expense gl)
             :balance-type :balance-type-default
             :balance-status :balance-status-posted
             :side :leg-side-debit
             :amount total
             :currency currency}
            {:account-id (:payable gl)
             :balance-type :balance-type-default
             :balance-status :balance-status-posted
             :side :leg-side-credit
             :amount total
             :currency currency}]}))
