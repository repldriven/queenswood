(ns com.repldriven.queenswood.balance-query.store
  (:require
    [com.repldriven.queenswood.balance-domain.interface :as domain]
    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

;; must match bank-balance.store/store-name — same FDB store. Public
;; because a caller pairing accounts with balances in one scan has to
;; name this store to `fdb/merge-scan`, and a third hardcoded copy of
;; the string is worse than saying where the one copy lives.
(def store-name "balances")

(def transact fdb/transact)

;; must match transaction.store/legs-store-name: a balance is read from
;; the legs' SUM indexes, and requiring `transaction`, which applies legs
;; through `balance`, would make a cycle.
(def ^:private legs-store-name "transaction-legs")

(def ^:private account-leg-sum-index
  "TransactionLeg_sum_amount_by_account_bucket_side")

(def ^:private sub-ledger-leg-sum-index
  "TransactionLeg_sum_amount_by_bank_product_currency_bucket_side")

(defn- bucket-key
  [balance]
  ((juxt :account-id :balance-type :balance-status) balance))

(defn- leg-sum-aggregates
  [balance]
  (let [{:keys [account-id balance-type balance-status]} balance
        group (fn [side]
                [account-id
                 (schema/balance-type->int balance-type)
                 (schema/balance-status->int balance-status)
                 (schema/leg-side->int side)])]
    [[:sum account-leg-sum-index (group :leg-side-credit)]
     [:sum account-leg-sum-index (group :leg-side-debit)]]))

(defn with-leg-sums
  [txn balances snapshot?]
  (let [derived (filterv domain/derived? balances)]
    (if (empty? derived)
      balances
      (let [aggregate (if snapshot?
                        fdb/aggregate-records-snapshot
                        fdb/aggregate-records)
            sums (aggregate (fdb/open txn legs-store-name)
                            (into [] (mapcat leg-sum-aggregates) derived))
            by-bucket (zipmap (map bucket-key derived) (partition 2 sums))]
        (mapv (fn [balance]
                (if-let [[credit debit] (get by-bucket (bucket-key balance))]
                  (assoc balance :credit credit :debit debit)
                  balance))
              balances)))))

(defn find-balance
  [txn bank-id account-id balance-type currency balance-status]
  (let-nom>
    [result (fdb/transact
             txn
             (fn [txn]
               (some->> (fdb/load-record
                         (fdb/open txn store-name)
                         bank-id
                         account-id
                         (schema/balance-type->int balance-type)
                         currency
                         (schema/balance-status->int balance-status))
                        schema/pb->Balance
                        vector
                        ((fn [balances] (with-leg-sums txn balances false)))
                        first))
             :balance/find
             "Failed to load balance")]
    result))

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
                  (with-leg-sums
                   txn
                   (mapv schema/pb->Balance
                         (:records (fdb/scan-records
                                    (fdb/open txn store-name)
                                    {:prefix [bank-id account-id]
                                     :limit 100})))
                   false))
                :balance/list
                "Failed to list balances"))

(defn list-balances-of
  [txn bank-id account-ids snapshot-ids]
  (fdb/transact
   txn
   (fn [txn]
     (let [rows (map (fn [records] (mapv schema/pb->Balance records))
                     (fdb/scan-prefixes (fdb/open txn store-name)
                                        (mapv (fn [id] [bank-id id])
                                              account-ids)
                                        100))
           by-account (zipmap account-ids rows)
           read (fn [snapshot?]
                  (with-leg-sums txn
                                 (into []
                                       (comp (filter (fn [[id _]]
                                                       (= snapshot?
                                                          (contains?
                                                           snapshot-ids
                                                           id))))
                                             (mapcat val))
                                       by-account)
                                 snapshot?))]
       (merge-with (fn [_ summed] summed)
                   by-account
                   (group-by :account-id
                             (concat (read false) (read true))))))
   :balance/list
   "Failed to list balances"))

(defn sum-bucket
  [txn bank-id product-type currency balance-type balance-status isolation]
  (fdb/transact
   txn
   (fn [txn]
     (let [aggregate (if (= :serializable isolation)
                       fdb/aggregate-records
                       fdb/aggregate-records-snapshot)
           group (fn [side]
                   [bank-id
                    (schema/product-type->int product-type)
                    currency
                    (schema/balance-type->int balance-type)
                    (schema/balance-status->int balance-status)
                    (schema/leg-side->int side)])
           [credit debit] (aggregate
                           (fdb/open txn legs-store-name)
                           [[:sum sub-ledger-leg-sum-index
                             (group :leg-side-credit)]
                            [:sum sub-ledger-leg-sum-index
                             (group :leg-side-debit)]])]
       {:credit credit :debit debit}))
   :balance/sum
   "Failed to sum balances"))
