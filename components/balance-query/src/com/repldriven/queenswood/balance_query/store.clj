(ns com.repldriven.queenswood.balance-query.store
  (:require
    [com.repldriven.queenswood.balance-query.domain :as domain]

    [com.repldriven.queenswood.fdb.interface :as fdb]
    [com.repldriven.queenswood.schema.interface :as schema]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

;; must match balance.store/store-name — same FDB store. Public
;; because a caller pairing accounts with balances in one scan has to
;; name this store to `fdb/merge-scan`, and a third hardcoded copy of
;; the string is worse than saying where the one copy lives.
(def store-name "account-balances")

(def transact fdb/transact)

;; must match transaction.store/legs-store-name: a balance is read from
;; the legs' SUM indexes, and requiring `transaction`, which applies legs
;; through `balance`, would make a cycle.
(def ^:private legs-store-name "transaction-legs")

(def ^:private account-leg-sum-index
  "TransactionLeg_sum_amount_by_account_bucket_side")

(def ^:private sub-ledger-leg-sum-index
  "TransactionLeg_sum_amount_by_bank_product_currency_bucket_side")

(defn- start-account-sums
  "Starts one scan of each account's leg sums, every bucket and side, and
  returns a function that waits on them and returns a map of bucket key
  to `[credit debit]`, so the rows can be scanned meanwhile."
  [txn account-ids snapshot?]
  (let [store (fdb/open txn legs-store-name)
        scans (mapv (fn [id]
                      (fdb/sum-groups-later store
                                            account-leg-sum-index
                                            [id]
                                            {:isolation (if snapshot?
                                                          :snapshot
                                                          :serializable)}))
                    account-ids)
        credit (schema/leg-side->int :leg-side-credit)]
    (fn []
      (reduce (fn [by-bucket [[id balance-type balance-status side] sum]]
                (update by-bucket
                        [id balance-type balance-status]
                        (fnil
                         (fn [[c d]]
                           (if (= credit side) [(+ c sum) d] [c (+ d sum)]))
                         [0 0])))
              {}
              (mapcat (fn [scan] (scan)) scans)))))

(defn- summed
  "`balances` with each derived bucket given the sums in `by-bucket`,
  keyed by account and the bucket's type and status as their ints. A
  derived bucket no leg reached sums to zero."
  [balances by-bucket]
  (mapv (fn [balance]
          (if (domain/derived? balance)
            (let [{:keys [account-id balance-type balance-status]} balance
                  [credit debit] (get by-bucket
                                      [account-id
                                       (schema/balance-type->int balance-type)
                                       (schema/balance-status->int
                                        balance-status)]
                                      [0 0])]
              (assoc balance :credit credit :debit debit))
            balance))
        balances))

(defn with-leg-sums
  [txn balances snapshot?]
  (let [ids (into []
                  (comp (filter domain/derived?) (map :account-id) (distinct))
                  balances)]
    (if (empty? ids)
      balances
      (summed balances ((start-account-sums txn ids snapshot?))))))

(defn find-balance
  [txn bank-id account-id balance-type balance-status]
  (let-nom>
    [result (fdb/transact
             txn
             (fn [txn]
               (some->> (fdb/load-record
                         (fdb/open txn store-name)
                         bank-id
                         account-id
                         (schema/balance-type->int balance-type)
                         (schema/balance-status->int balance-status))
                        schema/pb->AccountBalance
                        vector
                        ((fn [balances] (with-leg-sums txn balances false)))
                        first))
             :balance/find
             "Failed to load balance")]
    result))

(defn get-balance
  [txn bank-id account-id balance-type balance-status]
  (let-nom>
    [balance (find-balance txn bank-id account-id balance-type balance-status)]
    (or balance
        (error/reject :balance/not-found
                      {:message "Balance not found"
                       :bank-id bank-id
                       :account-id account-id
                       :balance-type balance-type
                       :balance-status balance-status}))))

(defn get-balances
  [txn bank-id account-id]
  (fdb/transact txn
                (fn [txn]
                  (let [sums (start-account-sums txn [account-id] false)
                        rows (mapv schema/pb->AccountBalance
                                   (:records (fdb/scan-records
                                              (fdb/open txn store-name)
                                              {:prefix [bank-id account-id]
                                               :limit 100})))]
                    (summed rows (sums))))
                :balance/list
                "Failed to list balances"))

(defn list-balances-of
  [txn bank-id account-ids snapshot-ids stored-ids]
  (fdb/transact
   txn
   (fn [txn]
     (let [{stored true summed-ids false}
           (group-by (fn [id] (contains? stored-ids id)) account-ids)
           {snapshot true serializable false}
           (group-by (fn [id] (contains? snapshot-ids id)) summed-ids)
           snapshot-sums (start-account-sums txn snapshot true)
           serializable-sums (start-account-sums txn serializable false)
           store (fdb/open txn store-name)
           prefixes (fn [ids] (mapv (fn [id] [bank-id id]) ids))
           rows (merge (zipmap stored
                               (fdb/scan-prefixes store (prefixes stored) 100))
                       (zipmap summed-ids
                               (fdb/scan-prefixes store
                                                  (prefixes summed-ids)
                                                  100
                                                  {:isolation :snapshot})))
           balances (update-vals rows
                                 (fn [records]
                                   (mapv schema/pb->AccountBalance records)))
           unsummed (filterv (fn [id] (some domain/derived? (get balances id)))
                             stored)
           by-bucket (merge (snapshot-sums)
                            (serializable-sums)
                            ((start-account-sums txn unsummed false)))]
       (zipmap account-ids
               (map (fn [id] (summed (get balances id) by-bucket))
                    account-ids))))
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
