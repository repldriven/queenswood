(ns com.repldriven.queenswood.bank-query.core
  (:require
    [com.repldriven.queenswood.bank-query.store :as store]

    [com.repldriven.queenswood.balance-query.interface :as balances]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-accounts-query]
    [com.repldriven.queenswood.party-query.interface :as party-query]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- enrich-accounts
  [txn bank-id accounts]
  (reduce (fn [acc account]
            (let [bal (balances/get-balances txn
                                             bank-id
                                             (:account-id account)
                                             (:currency account))]
              (if (error/anomaly? bal)
                (reduced bal)
                (conj acc (merge account bal)))))
          []
          accounts))

(defn- enrich
  [txn bank]
  (let [{:keys [bank-id]} bank]
    (let-nom>
      [{:keys [parties]} (party-query/get-parties txn bank-id)
       {:keys [accounts]} (cash-accounts-query/get-accounts txn bank-id)
       enriched (enrich-accounts txn bank-id accounts)]
      (assoc (dissoc bank :created-by :idempotency-key)
             :party (first parties)
             :accounts enriched
             :client-id bank-id))))

(defn get-bank-view
  [txn bank-id]
  (store/transact txn
                  (fn [txn]
                    (let-nom> [bank (store/get-bank txn bank-id)]
                      (enrich txn bank)))))

(defn get-banks
  ([txn] (get-banks txn nil))
  ([txn opts]
   (store/transact
    txn
    (fn [txn]
      (let-nom> [{:keys [banks] :as found} (store/get-banks txn opts)
                 enriched (reduce (fn [acc bank]
                                    (let [result (enrich txn bank)]
                                      (if (error/anomaly? result)
                                        (reduced result)
                                        (conj acc result))))
                                  []
                                  banks)]
        (assoc found :banks enriched))))))
