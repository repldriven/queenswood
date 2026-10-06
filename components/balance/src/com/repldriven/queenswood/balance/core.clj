(ns com.repldriven.queenswood.balance.core
  (:require
    [com.repldriven.queenswood.balance.domain :as domain]
    [com.repldriven.queenswood.balance.store :as store]

    [com.repldriven.queenswood.balance-domain.interface :as balance-math]
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
      (let [{:keys [account-id balance-type currency balance-status]} data]
        (let-nom>
          [policies (get-policies txn account-id opts)
           existing (q/find-balance txn
                                    bank-id
                                    account-id
                                    balance-type
                                    currency
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

(defn accrue
  [txn balance whole-units carry]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [updated (domain/accrue balance whole-units carry)
        _ (store/save-balance txn updated)]
       updated))))

(defn- load-account-balances
  [txn bank-id legs]
  (let [by-account (group-by :account-id legs)]
    (q/list-balances-of txn
                        bank-id
                        (vec (keys by-account))
                        {:snapshot-ids (into #{}
                                             (comp
                                              (remove
                                               (fn [[_ account-legs]]
                                                 (balance-math/lowers-available?
                                                  account-legs)))
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
                           (load-account-balances txn bank-id legs))
         changed (domain/apply-legs bank-id
                                    account-balances
                                    legs
                                    transaction-type
                                    policies)]
        (telemetry/with-span ["balance-save"]
                             (store/save-balances txn changed)))))))
