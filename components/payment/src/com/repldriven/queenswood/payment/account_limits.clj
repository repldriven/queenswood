(ns com.repldriven.queenswood.payment.account-limits
  (:require
    [com.repldriven.queenswood.payment.provider :as provider]

    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.payment-query.interface :as q]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]))

(defn- version-limits
  [config txn account]
  (let [{:keys [bank-id product-id version-id]} account]
    (provider/cached config
                     [:version-limits version-id]
                     (fn []
                       (let-nom> [version (products/get-version txn
                                                                bank-id
                                                                product-id
                                                                version-id)]
                         (vec (:limits version)))))))

(defn- periods
  "The periods `policies` count `measure` of an account's payments in
  `direction` over."
  [policies direction measure currency]
  (policy/periods policies
                  :account-payment
                  {:direction direction
                   :aggregate measure
                   :value {:currency currency}}))

(defn- read-period
  [txn account direction business-day period]
  (if (= :time-window-instant (:window period))
    {:count 0 :sum 0}
    (let [{:keys [bank-id account-id version-from-on]} account]
      (q/get-account-aggregates txn
                                bank-id
                                account-id
                                direction
                                (policy/period-start period
                                                     business-day
                                                     version-from-on)
                                business-day))))

(defn get-aggregates
  "What one account's payments in `direction` are held to: the
  `:policies` in force for it, the bank's `policies` and its product
  version's limits, and the `:aggregates` they count, one entry per
  period, `{:account-payment {#{account-id direction period} n}}` for a
  count and with `:amount` in the set for a sum. A period runs from the
  day the account's version started for it. Nothing is read for an
  account no limit bounds."
  [config txn policies account direction business-day]
  (telemetry/with-span
   ["payment-account-aggregates"]
   (let [{:keys [account-id currency]} account]
     (let-nom>
       [limits (version-limits config txn account)]
       (let [policies (cond-> (vec policies)
                              (seq limits)
                              (conj (policy/limits->policy limits)))
             counted (periods policies direction :count currency)
             summed (periods policies direction :amount currency)]
         (reduce
          (fn [acc period]
            (let [read (read-period txn account direction business-day period)]
              (if (error/anomaly? read)
                (reduced read)
                (cond-> acc
                        (contains? counted period)
                        (assoc-in [:aggregates :account-payment
                                   #{account-id direction period}]
                         (:count read))

                        (contains? summed period)
                        (assoc-in [:aggregates :account-payment
                                   #{account-id direction period :amount}]
                         (:sum read))))))
          {:policies policies :aggregates nil}
          (into counted summed)))))))
