(ns com.repldriven.queenswood.test-projections.interest
  (:require
    [com.repldriven.queenswood.balance-query.interface :as balance]
    [com.repldriven.queenswood.interest.interface :as interest]

    [com.repldriven.mono.error.interface :as error]))

(defn- accrued
  [bank bank-real-id account-id]
  (let [b (balance/get-balance bank
                               bank-real-id
                               account-id
                               :balance-type-interest-accrued
                               "GBP"
                               :balance-status-posted)
        carry (interest/accrual-carry bank bank-real-id account-id)]
    {:interest-accrued (if (error/anomaly? b) 0 (- (:credit b 0) (:debit b 0)))
     :credit-carry (if (error/anomaly? carry) 0 carry)}))

(defn project-interest
  [bank real->bank-id id-mapping]
  (->> id-mapping
       (map (fn [[real-id model-id]]
              [model-id (accrued bank (get real->bank-id real-id) real-id)]))
       (into {})))

(defn project-model-interest
  [model-state]
  (update-vals (:accounts model-state)
               (fn [account]
                 {:interest-accrued (:interest-accrued account 0)
                  :credit-carry (:credit-carry account 0)})))
