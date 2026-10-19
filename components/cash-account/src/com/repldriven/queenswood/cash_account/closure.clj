(ns com.repldriven.queenswood.cash-account.closure
  (:require
    [com.repldriven.queenswood.cash-account.domain :as domain]

    [com.repldriven.queenswood.balance-query.interface :as balances-q]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.cash-account-query.interface :as q]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn close
  "`account` as the close in `data` leaves it, `closing`, under
  `policies`."
  [txn data account policies]
  (let [{:keys [bank-id account-id]} account]
    (let-nom>
      [{:keys [operations]}
       (q/get-operations txn bank-id account-id {:limit 1})]
      (domain/close-account account
                            (first operations)
                            (:actor data)
                            policies))))

(defn terms
  "What the close in `data` of `account`, as it was before the close, is
  carried out by: its nominated account and the interest and fee terms
  its version holds it to today, the fee only where it was not already
  closing. Refuses a close its version refuses this early, a nominated
  account that does not qualify, and a balance with nowhere to go."
  [txn data account policies]
  (let [{:keys [nominated-account-id]} data
        {:keys [bank-id account-id product-id version-id]} account]
    (let-nom>
      [account-balances (balances-q/list-balances txn bank-id account-id)
       nominated (when nominated-account-id
                   (q/find-account txn bank-id nominated-account-id))
       version (products/get-version txn bank-id product-id version-id)
       term (domain/closure-term account
                                 (:closure-terms version)
                                 (utility/today))
       _ (domain/check-closable account
                                account-balances
                                term
                                nominated-account-id
                                nominated
                                policies)]
      (utility/assoc-some {:closure-interest (:interest term)}
                          :nominated-account-id
                          nominated-account-id
                          :closure-fee
                          (when-not (= :cash-account-status-closing
                                       (:status account))
                            (:fee term))))))
