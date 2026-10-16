(ns com.repldriven.queenswood.interest.accrue
  (:require
    [com.repldriven.queenswood.interest.domain.accrual :as accrual]
    [com.repldriven.queenswood.interest.domain.chart :as chart]
    [com.repldriven.queenswood.interest.store :as store]
    [com.repldriven.queenswood.interest.versions :as versions]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.transaction.interface :as transactions]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(def ^:private platform
  "The actor of what the interest brick records on its own, each day's
  accrual and capitalisation."
  {:kind :actor-kind-platform :principal-id "interest"})

(defn- get-day-rates
  "The day rates `account` accrues against on the run's business day,
  memoised for the run by its version and, where the version's schedule
  counts from it, the day the account came onto it, so a version's
  period and year are worked out once for the accounts sharing them
  rather than once for each."
  [ctx version account]
  (let [{:keys [day-rates business-day]} ctx
        accrual (get-in version [:interest-terms :accrual])
        k (accrual/day-rates-key account accrual)]
    (if (contains? @day-rates k)
      (get @day-rates k)
      (let [rates (accrual/day-rates accrual
                                     (:version-from-on account)
                                     business-day)]
        (swap! day-rates assoc k rates)
        rates))))

(defn- accruals
  "Each account in `chunk` paired with its day's accrual, opening with
  its carry in `carries`. An account with nothing to accrue is left
  out."
  [config ctx chunk carries]
  (let [{:keys [bank-id]} ctx]
    (reduce (fn [acc [account balances]]
              (let [result (let-nom>
                             [version (versions/get-version config
                                                            ctx
                                                            bank-id
                                                            account)]
                             (accrual/accrue
                              account
                              balances
                              (get carries (:account-id account) 0)
                              (get-day-rates ctx
                                             version
                                             account)))]
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
       recording (accrual/chunk-transactions bank-id
                                             expense
                                             accrued
                                             business-day)
       _ (reduce (fn [_ transaction]
                   (let [checked (ledger-accounts/accept-legs
                                  txn
                                  bank-id
                                  (:currency transaction)
                                  (:legs transaction))]
                     (when (error/anomaly? checked) (reduced checked))))
                 nil
                 recording)
       _ (transactions/record-transactions
          txn
          (mapv (fn [transaction] (assoc transaction :actor platform))
                recording))]
      (into {}
            (map (fn [[account result]] [(:account-id account) result]))
            accrued))))

(def pass
  "Everything a run of this kind does differently from the other."
  {:policy-kind :accrual
   :run-kind :interest-run-kind-accrue
   :account-kind :interest-run-kind-accrue
   :chunk-fn accrue-chunk
   :gl-fn chart/accrual-accounts})
