(ns com.repldriven.queenswood.interest.capitalize
  (:require
    [com.repldriven.queenswood.interest.domain.capitalization :as
     capitalization]
    [com.repldriven.queenswood.interest.domain.chart :as chart]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as transactions]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- record
  "Records one account's capitalisation, returning its recorded legs."
  [txn bank-id transaction]
  (let-nom>
    [legs (ledger-accounts/ensure-controls txn
                                           bank-id
                                           (:currency transaction)
                                           (:legs transaction))
     recorded (transactions/record-transaction txn
                                               (assoc transaction :legs legs))]
    (:legs recorded)))

(defn- capitalize-chunk
  "Each account in a chunk has its accrued interest swept into its
  spendable balance. Its transaction is the customer's statement line,
  moving the amount from the account's interest-accrued bucket to its
  default one, and so from 2400 to the deposit control its product type
  rolls into. Returns each account's sweep by account id.

  The legs are applied together once every transaction is recorded, so
  the chunk reads its accounts' sums in one round trip: at snapshot,
  since a credit reads nothing a payment writes, unless a limit in force
  caps the account's balance."
  [_config ctx txn chunk]
  (let [{:keys [bank-id business-day policies]} ctx
        swept (into []
                    (keep (fn [[account balances]]
                            (when-let [sweep (capitalization/sweep
                                              bank-id
                                              account
                                              (:currency account)
                                              balances
                                              business-day)]
                              [account sweep])))
                    chunk)]
    (let-nom>
      [legs (reduce (fn [acc [_ sweep]]
                      (let [legs (record txn bank-id (:transaction sweep))]
                        (if (error/anomaly? legs)
                          (reduced legs)
                          (into acc legs))))
                    []
                    swept)
       _ (when (seq legs)
           (balances/apply-legs txn
                                bank-id
                                legs
                                :transaction-type-interest-capital
                                {:policies (policy/platform-policies
                                            policies)}))]
      (into {}
            (map (fn [[account sweep]] [(:account-id account) sweep]))
            swept))))

(def pass
  "Everything a run of this kind does differently from the other."
  {:policy-kind :capitalize
   :run-kind :interest-run-kind-capitalize
   :account-kind :interest-account-run-kind-capitalize
   :chunk-fn capitalize-chunk
   :gl-fn chart/capitalization-accounts})
