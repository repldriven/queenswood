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
  "A day's accrual for one account opening with the sub-unit carry
  `opening-carry`: the `:principal` it was computed from, the `:amount`
  earned, and the `:closing-carry` left over with its `:carry-change`.

  Returns nil when there is nothing to accrue, either:
  * the product pays no interest, or
  * it pays interest but the account has no accrued interest balance to
    hold it."
  [account-id currency account-balances opening-carry interest-rate-bps]
  (let [principal (balances/principal-amount account-balances currency)
        accrued (day-interest principal opening-carry interest-rate-bps)]
    (when accrued
      (if (balances/accrued-interest-balance account-balances currency)
        (assoc accrued
               :principal principal
               :opening-carry opening-carry
               :carry-change (- (:closing-carry accrued) opening-carry))
        (do (log/warnf (str "Account %s has a non-zero interest rate but no"
                            " accrual balance - interest is not being accrued"
                            " for this account.")
                       account-id)
            nil)))))

(defn- leg
  [account-id product-type balance-type currency amount]
  (utility/assoc-some {:account-id account-id
                       :balance-type balance-type
                       :balance-status :balance-status-posted
                       :side (if (neg? amount) :leg-side-debit :leg-side-credit)
                       :amount (abs amount)
                       :currency currency}
                      :product-type
                      product-type))

(defn chunk-transactions
  "The accrual transactions for one chunk of `accruals`, each a pair of
  an account and what it accrued: one per currency, moving each
  account's interest-accrued bucket by what it earned, a credit, or by
  what an overdrawn principal took, a debit, and interest expense,
  `(expense-id currency)`, by the opposite of their total. An account
  that moved by nothing whole has no leg, and a currency none of whose
  accounts did has no transaction.

  Keyed by the run and the chunk's first account, so a chunk the run
  retries after its commit was lost records once."
  [bank-id expense-id accruals as-of-date]
  (into []
        (keep
         (fn [[currency pairs]]
           (let [moved (filter (fn [[_ {:keys [amount]}]] (not (zero? amount)))
                               pairs)
                 total (transduce (map (comp :amount second)) + 0 moved)]
             (when (seq moved)
               {:bank-id bank-id
                :idempotency-key (str "accrue-"
                                      bank-id
                                      "-"
                                      as-of-date
                                      "-"
                                      currency
                                      "-"
                                      (:account-id (ffirst pairs)))
                :transaction-type :transaction-type-interest-accrual
                :currency currency
                :reference (str "Daily interest accrual "
                                (utility/epoch-day->iso-date as-of-date))
                :legs (cond-> (mapv (fn [[account {:keys [amount]}]]
                                      (leg (:account-id account)
                                           (:product-type account)
                                           :balance-type-interest-accrued
                                           currency
                                           amount))
                                    moved)

                              (not (zero? total))
                              (conj (leg (expense-id currency)
                                         nil
                                         :balance-type-default
                                         currency
                                         (- total))))})))
         (group-by (fn [[account]] (:currency account)) accruals))))
