(ns com.repldriven.queenswood.balance.core
  (:require
    [com.repldriven.queenswood.balance.domain :as domain]
    [com.repldriven.queenswood.balance.store :as store]

    [com.repldriven.queenswood.balance-query.interface :as q]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]))

(defn- get-policies
  [txn account-id opts]
  (or (:policies opts)
      (policy/get-effective-policies txn {:account-id account-id})))

(defn new-balance
  ([txn bank-id data]
   (new-balance txn bank-id data {}))
  ([txn bank-id data opts]
   (store/transact
    txn
    (fn [txn]
      (let [{:keys [account-id balance-type balance-status]} data]
        (let-nom>
          [policies (get-policies txn account-id opts)
           existing (q/find-balance txn
                                    bank-id
                                    account-id
                                    balance-type
                                    balance-status)
           balance (domain/new-balance (assoc data :bank-id bank-id)
                                       (some? existing)
                                       policies)
           _ (store/save-balance txn balance)]
          balance))))))

(defn new-balances
  ([txn bank-id data]
   (new-balances txn bank-id data {}))
  ([txn bank-id data opts]
   (store/transact
    txn
    (fn [txn]
      (let-nom>
        [policies (get-policies txn (:account-id (first data)) opts)]
        (reduce (fn [acc item]
                  (let [result (new-balance txn
                                            bank-id
                                            item
                                            {:policies policies})]
                    (if (error/anomaly? result)
                      (reduced result)
                      (conj acc result))))
                []
                data))))))

(defn- bounded?
  "Whether a limit in force bounds the way `account-legs` move the
  account's available balance: a floor against a posting that lowers
  it, a cap against one that raises it. Only then does the check need
  the account's sums read serializably."
  [policies transaction-type account-legs]
  (let [delta (q/available-delta account-legs)
        sides (policy/bound-sides policies
                                  :balance
                                  {:kind {:computed {:name "available"}}
                                   :transaction-type transaction-type
                                   :aggregate :amount
                                   :window :time-window-instant
                                   :value {:value 0
                                           :currency (:currency
                                                      (first account-legs))}})]
    (or (and (neg? delta) (contains? sides :min))
        (and (pos? delta) (contains? sides :max)))))

(defn- cash-account?
  "Whether `account-legs` post to a cash account, whose default buckets
  are its legs' sums, rather than a ledger account keeping its balances
  in its rows."
  [account-legs]
  (q/derived? {:balance-type :balance-type-default
               :product-type (:product-type (first account-legs))}))

(defn- load-account-balances
  [txn bank-id legs transaction-type policies]
  (let [by-account (group-by :account-id legs)]
    (q/list-balances-of
     txn
     bank-id
     (vec (keys by-account))
     {:snapshot-ids (into #{}
                          (comp (remove (fn [[_ account-legs]]
                                          (bounded? policies
                                                    transaction-type
                                                    account-legs)))
                                (map key))
                          by-account)
      :stored-ids (into #{}
                        (comp (remove (fn [[_ account-legs]]
                                        (cash-account? account-legs)))
                              (map key))
                        by-account)})))

(defn apply-legs
  ([txn bank-id legs transaction-type]
   (apply-legs txn bank-id legs transaction-type {}))
  ([txn bank-id legs transaction-type opts]
   (store/transact
    txn
    (fn [txn]
      (let-nom>
        [policies (telemetry/with-span
                   ["balance-policies"]
                   (get-policies txn (:account-id (first legs)) opts))
         account-balances (telemetry/with-span
                           ["balance-load"]
                           (load-account-balances txn
                                                  bank-id
                                                  legs
                                                  transaction-type
                                                  policies))
         changed (domain/apply-legs bank-id
                                    account-balances
                                    legs
                                    transaction-type
                                    policies)]
        (telemetry/with-span ["balance-save"]
                             (store/save-balances txn changed)))))))
