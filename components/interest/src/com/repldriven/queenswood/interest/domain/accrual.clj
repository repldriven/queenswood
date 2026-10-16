(ns com.repldriven.queenswood.interest.domain.accrual
  (:require
    [com.repldriven.queenswood.interest.domain.balances :as balances]

    [com.repldriven.queenswood.calc.interface :as calc]

    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(def ^:private bandings
  {:interest-banding-marginal :marginal
   :interest-banding-whole-balance :whole-balance})

(def ^:private day-counts
  {:interest-day-count-actual-365 :actual-365
   :interest-day-count-actual-actual :actual-actual
   :interest-day-count-actual-360 :actual-360})

(def ^:private units
  {:interest-period-unit-day :day
   :interest-period-unit-week :week
   :interest-period-unit-month :month})

(defn- calc-accrual
  "A version's `accrual` terms in calc's vocabulary."
  [accrual]
  (let [{:keys [banding day-count bands schedule]} accrual
        {:keys [relative fixed]} schedule]
    {:banding (bandings banding)
     :day-count (day-counts day-count)
     :bands bands
     :schedule (if relative
                 {:relative (update relative :unit units)}
                 {:fixed fixed})}))

(defn day-rates
  "What every account on a version accrues against on `business-day`
  under its `accrual` terms, counting a relative schedule from
  `version-from-on`, as calc's day rates; nil where the version pays no
  interest that day."
  [accrual version-from-on business-day]
  (when accrual
    (calc/day-rates (calc-accrual accrual) version-from-on business-day)))

(defn day-rates-key
  "What an account's day rates on a version depend on besides the day:
  the version, and the day the account came onto it where the schedule
  counts from it."
  [account accrual]
  (let [{:keys [product-id version-id version-from-on]} account]
    [product-id
     version-id
     (when (get-in accrual [:schedule :relative]) version-from-on)]))

(defn accrue
  "A day's accrual for `account` at `day-rates`, opening with the
  sub-unit carry `opening-carry`: the `:principal` it was computed from,
  the `:amount` earned, and the `:closing-carry` left over with its
  `:carry-delta`.

  Returns nil when there is nothing to accrue, either:
  * the version pays no interest, or none on this principal on this day,
    or
  * it pays interest but the account has no accrued interest balance to
    hold it."
  [account account-balances opening-carry day-rates]
  (let [{:keys [account-id currency]} account
        principal (balances/principal-amount account-balances currency)
        accrued (when day-rates
                  (calc/accrue day-rates principal opening-carry))]
    (when accrued
      (if (balances/accrued-interest-balance account-balances)
        {:amount (:amount accrued)
         :closing-carry (:carry accrued)
         :principal principal
         :opening-carry opening-carry
         :carry-delta (- (:carry accrued) opening-carry)}
        (do (log/warnf (str "Account %s has a non-zero interest rate but no"
                            " accrual balance - interest is not being accrued"
                            " for this account.")
                       account-id)
            nil)))))

(defn- leg
  [account-id product-type balance-type currency amount]
  {:account-id account-id
   :product-type product-type
   :balance-type balance-type
   :balance-status :balance-status-posted
   :side (if (neg? amount) :leg-side-debit :leg-side-credit)
   :amount (abs amount)
   :currency currency})

(defn chunk-transactions
  "The accrual transactions for one chunk of `accruals`, each a pair of
  an account and what it accrued: one per currency, moving each
  account's interest-accrued bucket by what it earned, a credit, or by
  what a negative rate took, a debit, and interest expense,
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
                                         :account-product-type-general-ledger
                                         :balance-type-default
                                         currency
                                         (- total))))})))
         (group-by (fn [[account]] (:currency account)) accruals))))
