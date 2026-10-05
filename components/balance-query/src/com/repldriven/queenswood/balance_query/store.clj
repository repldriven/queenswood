(ns com.repldriven.queenswood.balance-query.store
  (:require
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

;; must match bank-balance.store/store-name — same FDB store. Public
;; because a caller pairing accounts with balances in one scan has to
;; name this store to `fdb/merge-scan`, and a third hardcoded copy of
;; the string is worse than saying where the one copy lives.
(def store-name "balances")

(def transact fdb/transact)

(defn find-balance
  [txn bank-id account-id balance-type currency balance-status]
  (let-nom>
    [result (fdb/transact
             txn
             (fn [txn]
               (fdb/load-record
                (fdb/open txn store-name)
                bank-id
                account-id
                (schema/balance-type->int balance-type)
                currency
                (schema/balance-status->int balance-status)))
             :balance/find
             "Failed to load balance")]
    (when result (schema/pb->Balance result))))

(defn get-balance
  [txn bank-id account-id balance-type currency balance-status]
  (let-nom>
    [balance (find-balance txn
                           bank-id
                           account-id
                           balance-type
                           currency
                           balance-status)]
    (or balance
        (error/reject :balance/not-found
                      {:message "Balance not found"
                       :bank-id bank-id
                       :account-id account-id
                       :balance-type balance-type
                       :currency currency
                       :balance-status balance-status}))))

(defn get-balances
  [txn bank-id account-id]
  (fdb/transact txn
                (fn [txn]
                  (mapv schema/pb->Balance
                        (:records (fdb/scan-records
                                   (fdb/open txn store-name)
                                   {:prefix [bank-id account-id]
                                    :limit 100}))))
                :balance/list
                "Failed to list balances"))

(defn list-balances-of
  [txn bank-id account-ids]
  (fdb/transact txn
                (fn [txn]
                  (zipmap account-ids
                          (map (fn [records]
                                 (mapv schema/pb->Balance records))
                               (fdb/scan-prefixes
                                (fdb/open txn store-name)
                                (mapv (fn [id] [bank-id id]) account-ids)
                                100))))
                :balance/list
                "Failed to list balances"))

(def ^:private credit-sum-index
  "Balance_sum_credit_by_bank_product_currency_bucket")

(def ^:private debit-sum-index
  "Balance_sum_debit_by_bank_product_currency_bucket")

(defn sum-bucket
  [txn bank-id product-type currency balance-type balance-status isolation]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn store-name)
           sum (if (= :serializable isolation)
                 fdb/sum-records
                 fdb/sum-records-snapshot)
           group [bank-id
                  (schema/product-type->int product-type)
                  currency
                  (schema/balance-type->int balance-type)
                  (schema/balance-status->int balance-status)]]
       {:credit (sum store credit-sum-index group)
        :debit (sum store debit-sum-index group)}))
   :balance/sum
   "Failed to sum balances"))
