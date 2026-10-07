(ns com.repldriven.queenswood.reward.core
  (:require
    [com.repldriven.queenswood.reward.domain :as domain]
    [com.repldriven.queenswood.reward.store :as store]

    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.cache.interface :as cache]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]

    [clojure.tools.logging :as log]))

(defn- existing-row
  [txn account]
  (store/find-by-account txn
                         (:bank-id account)
                         (:account-id account)
                         :reward-kind-opening))

(defn- house-account
  "The bank's own-funds account for `currency`, from `config`'s `:cache`
  where the processor holds one, since it changes only when a bank is
  created. An anomaly is answered and not cached."
  [config txn bank-id currency]
  (let [load #(cash-accounts/house-account txn bank-id currency)]
    (if-let [c (:cache config)]
      (let [failure (volatile! nil)
            v (cache/lookup c
                            [:house-account bank-id currency]
                            (fn []
                              (let [v (load)]
                                (if (error/anomaly? v)
                                  (do (vreset! failure v) nil)
                                  v))))]
        (or @failure v))
      (load))))

(defn- owed
  "What `account` is owed, read in `txn`: `{:account :amount :existing}`,
  or nil where it is not eligible, its version promises nothing, or its
  reward is already paid."
  [txn bank-id account-id]
  (let-nom>
    [account (cash-accounts/get-account txn bank-id account-id)
     version (when (domain/eligible? account)
               (products/get-version txn
                                     bank-id
                                     (:product-id account)
                                     (:version-id account)))
     amount (domain/promised version)
     existing (when amount (existing-row txn account))]
    (when (and amount (not (domain/paid? existing)))
      {:account account :amount amount :existing existing})))

(defn- pay
  "Pays what `owed` finds in one transaction: the reads, the posting from
  the house account and the row `paid` commit together, so a redelivered
  entry finds the row paid and pays nothing. Returns the paid row, nil
  where nothing is owed, or the anomaly that refused it — in which case
  nothing landed."
  [config bank-id account-id]
  (store/transact
   config
   (fn [txn]
     (let-nom>
       [{:keys [account amount existing] :as due} (owed txn bank-id account-id)]
       (when due
         (let-nom>
           [house (house-account config txn bank-id (:currency account))
            reward (or existing (domain/new-reward account amount))
            transaction (domain/reward-transaction house account reward)
            legs (ledger-accounts/ensure-controls txn
                                                  bank-id
                                                  (:currency reward)
                                                  (:legs transaction))
            posted (transactions/record-and-post txn
                                                 bank-id
                                                 (assoc transaction
                                                        :legs
                                                        legs))]
           (store/save-reward txn
                              (domain/paid reward (:transaction-id posted))
                              {:change-kind :reward-change-kind-pay
                               :status-before (:status existing)})))))
   :reward/pay
   "Failed to pay reward"))

(defn- defer
  "Records that the account is owed its reward and why it was not paid,
  in a transaction of its own since the paying one rolled back. What is
  owed is read again, so a delivery that paid it meanwhile is left
  alone."
  [config bank-id account-id anomaly]
  (store/transact
   config
   (fn [txn]
     (let-nom>
       [{:keys [account amount existing] :as due} (owed txn bank-id account-id)]
       (when due
         (store/save-reward txn
                            (domain/deferred (or existing
                                                 (domain/new-reward account
                                                                    amount))
                                             anomaly)
                            {:change-kind :reward-change-kind-defer
                             :status-before (:status existing)}))))
   :reward/defer
   "Failed to record a deferred reward"))

(defn pay-opening
  [config {:keys [bank-id account-id]}]
  (let [result (pay config bank-id account-id)]
    (if (error/rejection? result)
      (do (log/warn "reward: deferred"
                    {:account-id account-id
                     :error (error/format-anomaly result)})
          (defer config bank-id account-id result))
      result)))
