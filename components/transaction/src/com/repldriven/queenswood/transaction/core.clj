(ns com.repldriven.queenswood.transaction.core
  (:require
    [com.repldriven.queenswood.transaction.domain :as domain]
    [com.repldriven.queenswood.transaction.store :as store]

    [com.repldriven.queenswood.balance.interface :as balances]

    [com.repldriven.mono.error.interface :refer [let-nom>]]))

(defn record
  [txn data]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [transaction (domain/new-transaction data)]
       (let [{:keys [legs]} data
             {:keys [transaction-id currency]} transaction
             legs' (mapv (fn [leg]
                           (domain/new-leg leg transaction-id currency))
                         legs)]
         (let-nom>
           [_ (domain/validate-legs legs)
            _ (store/save-transaction txn transaction)
            _ (store/save-legs txn legs')]
           (assoc transaction :legs legs')))))))

(defn- or-already-recorded
  "On a uniqueness violation — a redelivered record-transaction command
  carrying an already-seen idempotency-key — read the existing
  transaction back and return it, so the caller gets the original
  resource instead of a bare rejection. Any other value passes through
  unchanged."
  [txn data result]
  (if (and (store/uniqueness-violation? result)
           (:idempotency-key data))
    (let-nom> [existing (store/find-transaction-by-idempotency-key
                         txn
                         (:bank-id data)
                         (:transaction-type data)
                         (:idempotency-key data))]
      (or existing result))
    result))

(defn record-and-post
  [txn bank-id data]
  (let [data (assoc data :bank-id bank-id)]
    (or-already-recorded
     txn
     data
     (store/transact
      txn
      (fn [txn]
        (let-nom>
          [result (record txn data)
           _ (balances/apply-legs txn
                                  bank-id
                                  (:legs result)
                                  (:transaction-type result))]
          result))))))
