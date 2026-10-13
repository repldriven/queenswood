(ns com.repldriven.queenswood.payment.account-limits
  (:require
    [com.repldriven.queenswood.payment.provider :as provider]

    [com.repldriven.queenswood.balance-query.interface :as balances]
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

(defn- ceiling?
  "Whether a limit in `policies` caps the available balance of an account
  a payment in `currency` is paid into from outside."
  [policies currency]
  (contains? (policy/bound-sides policies
                                 :balance
                                 {:kind {:computed {:name "available"}}
                                  :transaction-type
                                  :transaction-type-inbound-transfer
                                  :aggregate :amount
                                  :window :time-window-instant
                                  :value {:value 0 :currency currency}})
             :max))

(defn- with-available
  "`limits` with the account's available balance among its aggregates,
  read serializably, where a limit caps what a payment in may leave it
  holding: a payment from outside is refused before it is recorded, so
  its cap is checked beside its other limits."
  [txn account direction limits]
  (let [{:keys [bank-id account-id currency]} account]
    (if (and (= :payment-direction-in direction)
             (ceiling? (:policies limits) currency))
      (let-nom> [balances (balances/get-balances txn
                                                 bank-id
                                                 account-id
                                                 currency)]
        (assoc-in limits
         [:aggregates :balance #{account-id :available}]
         (get-in balances [:available-balance :value])))
      limits)))

(defn version-policies
  "A map of account id to the policies its product version adds, for
  `balance/accept-legs`, from a map of account id to what
  `get-aggregates` returned for it."
  [limits-by-account]
  (into {}
        (keep (fn [[account-id {:keys [version-policy]}]]
                (when version-policy [account-id [version-policy]])))
        limits-by-account))

(defn get-aggregates
  "What one account's payments in `direction` are held to: the
  `:policies` in force for it, the bank's `policies` and its product
  version's limits, that `:version-policy` alone where it carries any,
  and the `:aggregates` they count, one entry per period,
  `{:account-payment {#{account-id direction period} n}}` for a count
  and with `:amount` in the set for a sum, and under `:balance` the
  account's available balance where a limit caps a payment in. A period
  runs from the day the account's version started for it. Nothing is
  read for an account no limit bounds."
  [config txn policies account direction business-day]
  (telemetry/with-span
   ["payment-account-aggregates"]
   (let [{:keys [account-id currency]} account]
     (let-nom>
       [limits (version-limits config txn account)]
       (let [version-policy (when (seq limits) (policy/limits->policy limits))
             policies (cond-> (vec policies)
                              version-policy
                              (conj version-policy))
             counted (periods policies direction :count currency)
             summed (periods policies direction :amount currency)]
         (with-available
          txn
          account
          direction
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
           {:policies policies :version-policy version-policy :aggregates nil}
           (into counted summed))))))))
