(ns com.repldriven.queenswood.transaction.core
  (:require
    [com.repldriven.queenswood.transaction.domain :as domain]
    [com.repldriven.queenswood.transaction.store :as store]

    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.bank-activity.interface :as bank-activity]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- prepare
  [data]
  (let-nom>
    [transaction (domain/new-transaction data)
     _ (domain/validate-legs (:legs data))]
    (assoc transaction
           :legs
           (mapv (fn [leg] (domain/new-leg leg transaction)) (:legs data)))))

(defn- record-posted
  [txn data transaction]
  (let [{:keys [transaction-id bank-id]} transaction]
    (bank-activity/record txn
                          {:bank-id bank-id
                           :event-name "transaction-posted"
                           :data (domain/posted transaction
                                                (:legs data)
                                                (:scheme-account-id data))
                           :causation-id transaction-id
                           :dedup-key transaction-id})))

(defn record
  [txn data]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [transaction (prepare data)
        _ (store/save-transaction-and-legs txn
                                           (dissoc transaction :legs)
                                           (:legs transaction))
        _ (record-posted txn data transaction)]
       transaction))))

(defn record-many
  [txn datas]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [transactions (reduce
                      (fn [acc data]
                        (let [t (prepare data)]
                          (if (error/anomaly? t) (reduced t) (conj acc t))))
                      []
                      datas)
        _ (store/save-transactions-and-legs txn transactions)
        _ (reduce (fn [_ [data transaction]]
                    (let [result (record-posted txn data transaction)]
                      (when (error/anomaly? result) (reduced result))))
                  nil
                  (map vector datas transactions))]
       transactions))))

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
