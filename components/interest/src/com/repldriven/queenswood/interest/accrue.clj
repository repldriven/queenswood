(ns com.repldriven.queenswood.interest.accrue
  (:require
    [com.repldriven.queenswood.interest.domain.accrual :as accrual]
    [com.repldriven.queenswood.interest.domain.chart :as chart]
    [com.repldriven.queenswood.interest.store :as store]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.transaction.interface :as transactions]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- get-product-version
  "The account's pinned product version, memoised for the run.

  The map is built from the accounts as they stream rather than from
  the bank's current products: an account pins `product-id` and
  `version-id` when it opens, and a pinned version may be one the bank
  no longer offers, so enumerating what is on offer would miss
  accounts. A bank has dozens of versions in use rather than millions,
  so after the first few accounts this is a hash lookup.

  Scoped to the run rather than a TTL cache, because a pass wants one
  view of the rates it is applying — an entry expiring mid-pass would
  accrue two halves of the same bank at two different rates."
  [config versions bank-id account]
  (let [k [(:product-id account) (:version-id account)]]
    (if-some [hit (get @versions k)]
      hit
      (let [version (products/get-version config
                                          bank-id
                                          (:product-id account)
                                          (:version-id account))]
        (when-not (error/anomaly? version)
          (swap! versions assoc k version))
        version))))

(defn- accruals
  "Each account in `chunk` paired with its day's accrual, opening with
  its carry in `carries`. An account with nothing to accrue is left
  out."
  [config ctx chunk carries]
  (let [{:keys [bank-id versions]} ctx]
    (reduce (fn [acc [account balances]]
              (let [{:keys [account-id currency]} account
                    result (let-nom>
                             [version (get-product-version config
                                                           versions
                                                           bank-id
                                                           account)]
                             (accrual/accrue account-id
                                             currency
                                             balances
                                             (get carries account-id 0)
                                             (:interest-rate-bps version)))]
                (cond (error/anomaly? result)
                      (reduced result)

                      (nil? result)
                      acc

                      :else
                      (conj acc [account result]))))
            []
            chunk)))

(defn- expense-ids
  "The bank's interest expense account in each currency `accruals` are
  in, as a map of currency to id."
  [ctx accruals]
  (let [{:keys [bank-id gl]} ctx]
    (reduce (fn [acc currency]
              (let [resolved (chart/accounts-for gl bank-id currency)]
                (if (error/anomaly? resolved)
                  (reduced resolved)
                  (assoc acc currency (:expense resolved)))))
            {}
            (distinct (map (comp :currency first) accruals)))))

(defn- accrue-chunk
  "A chunk's accrual: each account's day of interest, opening with the
  carry its earlier accruals left, recorded as one transaction per
  currency crediting each account's interest-accrued bucket and
  debiting interest expense. Returns each account's accrual by account
  id.

  It appends and reads nothing a payment writes: the principal was read
  by the scan, a carry is written only by its own account's accrual,
  and both buckets the legs reach are sums of legs, 2400 summing the
  interest-accrued buckets and 5100 its own legs, so no balance row is
  read or written."
  [config ctx txn chunk]
  (let [{:keys [bank-id business-day]} ctx]
    (let-nom>
      [carries (store/load-carries txn
                                   bank-id
                                   (mapv (comp :account-id first) chunk))
       accrued (accruals config ctx chunk carries)
       expense (expense-ids ctx accrued)
       _ (reduce (fn [_ transaction]
                   (let [result (let-nom>
                                  [legs (ledger-accounts/ensure-controls
                                         txn
                                         bank-id
                                         (:currency transaction)
                                         (:legs transaction))]
                                  (transactions/record-transaction
                                   txn
                                   (assoc transaction :legs legs)))]
                     (when (error/anomaly? result) (reduced result))))
                 nil
                 (accrual/chunk-transactions bank-id
                                             expense
                                             accrued
                                             business-day))]
      (into {}
            (map (fn [[account result]] [(:account-id account) result]))
            accrued))))

(def pass
  "Everything a run of this kind does differently from the other."
  {:policy-kind :accrual
   :run-kind :interest-run-kind-accrue
   :account-kind :interest-account-run-kind-accrue
   :chunk-fn accrue-chunk
   :gl-fn chart/accrual-accounts})
