(ns com.repldriven.queenswood.interest.capitalize
  (:require
    [com.repldriven.queenswood.interest.calendars :as calendars]
    [com.repldriven.queenswood.interest.domain.capitalization :as
     capitalization]
    [com.repldriven.queenswood.interest.domain.chart :as chart]
    [com.repldriven.queenswood.interest.domain.payment :as payment]
    [com.repldriven.queenswood.interest.store :as store]
    [com.repldriven.queenswood.interest.versions :as versions]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as transactions]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(def ^:private platform
  "The actor of what the interest brick records on its own, each day's
  accrual and capitalisation."
  {:kind :actor-kind-platform :principal-id "interest"})

(defn- record
  "Records the chunk's capitalisations, after checking each currency's
  controls once, returning their recorded legs."
  [txn bank-id transactions]
  (let-nom>
    [_ (reduce (fn [_ [currency txs]]
                 (let [checked (ledger-accounts/accept-legs
                                txn
                                bank-id
                                currency
                                (into [] (mapcat :legs) txs))]
                   (when (error/anomaly? checked) (reduced checked))))
               nil
               (group-by :currency transactions))
     recorded (transactions/record-transactions
               txn
               (mapv (fn [transaction] (assoc transaction :actor platform))
                     transactions))]
    (into [] (mapcat :legs) recorded)))

(defn- prepare
  "What a capitalisation run on `business-day` works out once: the day
  of the bank's last capitalisation run before it, from which its window
  runs, each calendar a payment may name, and an empty memo of which
  accounts are due."
  [config bank-id business-day]
  (let-nom>
    [last-run (store/last-run-before config
                                     bank-id
                                     business-day
                                     :interest-run-kind-capitalize)
     by-kind (calendars/calendars business-day)]
    {:paid-after (:business-day last-run)
     :calendars by-kind
     :due (atom {})}))

(defn- due?
  "Whether `account` is due a payment in the run's window under its
  `version`'s payment, memoised for the run by what that depends on."
  [ctx version account]
  (let [{:keys [paid-after business-day calendars due]} ctx
        terms (get-in version [:interest-terms :payment])]
    (when terms
      (let [k (payment/due-key account terms paid-after business-day)]
        (if (contains? @due k)
          (get @due k)
          (let [answer (payment/due? terms
                                     (:version-from-on account)
                                     (get calendars (:calendar terms) {})
                                     paid-after
                                     business-day)]
            (swap! due assoc k answer)
            answer))))))

(defn- due-accounts
  "The accounts in `chunk` due a payment in the run's window, each with
  its balances."
  [config ctx chunk]
  (let [{:keys [bank-id]} ctx]
    (reduce (fn [acc [account :as entry]]
              (let [version (versions/get-version config ctx bank-id account)]
                (cond (error/anomaly? version)
                      (reduced version)

                      (due? ctx version account)
                      (conj acc entry)

                      :else
                      acc)))
            []
            chunk)))

(defn- capitalize-chunk
  "Each account in a chunk due a payment in the run's window has its
  accrued interest swept into its spendable balance. Its transaction is
  the customer's statement line, moving the amount from the account's
  interest-accrued bucket to its default one, and so from 2400 to the
  deposit control its product type rolls into. Returns each account's
  sweep by account id.

  The legs are applied together once every transaction is recorded, so
  the chunk reads its accounts' sums in one round trip: at snapshot,
  since a credit reads nothing a payment writes, unless a limit in force
  caps the account's balance."
  [config ctx txn chunk]
  (let [{:keys [bank-id business-day policies]} ctx]
    (let-nom>
      [due (due-accounts config ctx chunk)
       swept (into []
                   (keep (fn [[account balances]]
                           (when-let [sweep (capitalization/sweep
                                             bank-id
                                             account
                                             (:currency account)
                                             balances
                                             business-day)]
                             [account sweep])))
                   due)
       legs (if (seq swept)
              (record txn bank-id (mapv (comp :transaction second) swept))
              [])
       _ (when (seq legs)
           (balances/accept-legs txn
                                 bank-id
                                 legs
                                 :transaction-type-interest-capitalization
                                 {:policies (policy/platform-policies
                                             policies)}))]
      (into {}
            (map (fn [[account sweep]] [(:account-id account) sweep]))
            swept))))

(def pass
  "Everything a run of this kind does differently from the other."
  {:policy-kind :capitalize
   :run-kind :interest-run-kind-capitalize
   :account-kind :interest-run-kind-capitalize
   :prepare-fn prepare
   :chunk-fn capitalize-chunk
   :gl-fn chart/capitalization-accounts})
