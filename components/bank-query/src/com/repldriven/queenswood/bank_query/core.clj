(ns com.repldriven.queenswood.bank-query.core
  (:require
    [com.repldriven.queenswood.bank-query.store :as store]

    [com.repldriven.queenswood.balance-query.interface :as balances]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products-query]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-accounts-query]
    [com.repldriven.queenswood.party-query.interface :as party-query]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- account-gl-code
  "Resolve the GL code for an account by reading its product version's
  denormalised top-level gl-code. Nil for customer (sub-ledger)
  accounts. Lets callers select a specific GL account (e.g. the 1100
  settlement) by code rather than by seed order."
  [txn bank-id {:keys [product-id version-id]}]
  (let [version (products-query/get-version txn bank-id product-id version-id)]
    (when-not (error/anomaly? version)
      (:gl-code version))))

(defn- enrich-accounts
  [txn bank-id accounts]
  (reduce (fn [acc account]
            (let [bal (balances/get-balances txn
                                             bank-id
                                             (:account-id account))]
              (if (error/anomaly? bal)
                (reduced bal)
                (let [gl-code (account-gl-code txn bank-id account)
                      enriched (cond-> (merge account bal)
                                       gl-code
                                       (assoc :gl-code gl-code))]
                  (conj acc enriched)))))
          []
          accounts))

(defn- enrich
  [txn bank]
  (let [{:keys [bank-id]} bank]
    (let-nom>
      [{:keys [parties]} (party-query/get-parties txn bank-id)
       {:keys [accounts]} (cash-accounts-query/get-accounts txn bank-id)
       enriched (enrich-accounts txn bank-id accounts)]
      (assoc bank
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
