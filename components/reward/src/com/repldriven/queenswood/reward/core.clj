(ns com.repldriven.queenswood.reward.core
  (:require
    [com.repldriven.queenswood.reward.domain :as domain]
    [com.repldriven.queenswood.reward.store :as store]

    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.tools.logging :as log]))

(defn- memoised
  "The value under `k` in the run-scoped `cache`, computed once by `f`.
  An anomaly is answered but never kept, so a read that failed is tried
  again by the next account that needs it."
  [cache k f]
  (if-some [hit (get @cache k)]
    hit
    (let [value (f)]
      (when-not (error/anomaly? value) (swap! cache assoc k value))
      value)))

(defn- version-of
  "The account's pinned version, memoised for the run: one view of the
  terms across the pass, and a hash lookup after the first account on
  each version."
  [config ctx account]
  (memoised (:versions ctx)
            [(:product-id account) (:version-id account)]
            #(products/get-version config
                                   (:bank-id ctx)
                                   (:product-id account)
                                   (:version-id account))))

(defn- house-account-for
  [config ctx currency]
  (memoised (:house-accounts ctx)
            currency
            #(cash-accounts/house-account config (:bank-id ctx) currency)))

(defn- existing-row
  [txn ctx account]
  (store/find-by-account txn
                         (:bank-id ctx)
                         (:account-id account)
                         :reward-kind-opening))

(defn- pay
  "Pays `account` its reward from `house`, the posting and the row
  `paid` in one transaction. The row is read again inside it, so two
  runs reaching the same account pay it once: the second finds it paid
  and answers `:paid-before`. Returns the paid row, `:paid-before`, or
  the anomaly that refused the posting — in which case nothing landed."
  [config ctx house account amount]
  (store/transact
   config
   (fn [txn]
     (let [existing (existing-row txn ctx account)]
       (cond
        (error/anomaly? existing)
        existing
        (domain/paid? existing)
        :paid-before
        :else
        (let [reward (or existing
                         (domain/new-reward account amount (:run-id ctx)))
              transaction (domain/reward-transaction house account reward)]
          (let-nom>
            [legs (ledger-accounts/add-control-legs txn
                                                    (:bank-id ctx)
                                                    (:currency reward)
                                                    (:legs transaction))
             posted (transactions/record-and-post
                     txn
                     (:bank-id ctx)
                     (assoc transaction :legs legs))]
            (store/save-reward txn
                               (domain/paid reward
                                            (:transaction-id posted)
                                            (:run-id ctx))
                               {:change-kind :reward-change-kind-pay
                                :status-before (:status existing)}))))))
   :reward/pay
   "Failed to pay reward"))

(defn- defer
  "Records that `account` is owed its reward and why it was not paid,
  in a transaction of its own since the paying one rolled back."
  [config ctx account amount anomaly]
  (store/transact
   config
   (fn [txn]
     (let [existing (existing-row txn ctx account)]
       (cond
        (error/anomaly? existing)
        existing
        (domain/paid? existing)
        existing
        :else
        (store/save-reward txn
                           (domain/deferred (or existing
                                                (domain/new-reward
                                                 account
                                                 amount
                                                 (:run-id ctx)))
                                            anomaly
                                            (:run-id ctx))
                           {:change-kind :reward-change-kind-defer
                            :status-before (:status existing)}))))
   :reward/defer
   "Failed to record a deferred reward"))

(defn- consider
  "One account's outcome: `:ineligible`, `:unpromised`, `:paid-before`,
  `:paid` or `:deferred`. A read that fails before anything is owed —
  the version, or the row — defers nothing, since a row needs an
  amount, and is logged for the run to try again."
  [config ctx account]
  (if-not (domain/eligible? account)
    :ineligible
    (let [version (version-of config ctx account)]
      (cond
       (error/anomaly? version)
       (do (log/warn "reward: version unreadable, account left for the next run"
                     {:account-id (:account-id account)
                      :error (error/format-anomaly version)})
           :deferred)

       (nil? (domain/promised version))
       :unpromised

       :else
       (let [amount (domain/promised version)
             existing (existing-row config ctx account)]
         (cond
          (error/anomaly? existing)
          (do (log/warn "reward: row unreadable, account left for the next run"
                        {:account-id (:account-id account)
                         :error (error/format-anomaly existing)})
              :deferred)

          (domain/paid? existing)
          :paid-before

          :else
          (let [house (house-account-for config ctx (:currency account))
                result (if (error/anomaly? house)
                         house
                         (pay config ctx house account amount))]
            (cond
             (= :paid-before result)
             :paid-before
             (error/anomaly? result)
             (do (defer config ctx account amount result)
                 (log/warn "reward: deferred"
                           {:account-id (:account-id account)
                            :error (error/format-anomaly result)})
                 :deferred)
             :else
             :paid))))))))

(defn pay-due
  [config {:keys [bank-id as-of-date]}]
  (let [ctx {:bank-id bank-id
             :run-id (str (utility/uuidv7))
             :versions (atom {})
             :house-accounts (atom {})}]
    (let-nom>
      [tally (cash-accounts/reduce-accounts-with-balances
              config
              bank-id
              (fn [tally {:keys [account]}]
                (let [outcome (consider config ctx account)]
                  (cond-> tally
                          (= :paid outcome)
                          (update :paid inc)
                          (= :deferred outcome)
                          (update :deferred inc))))
              {:paid 0 :deferred 0})]
      {:bank-id bank-id
       :as-of-date as-of-date
       :accounts-processed (:paid tally)
       :accounts-failed (:deferred tally)})))
