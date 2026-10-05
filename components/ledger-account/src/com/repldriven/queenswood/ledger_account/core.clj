(ns com.repldriven.queenswood.ledger-account.core
  (:require
    [com.repldriven.queenswood.ledger-account.domain :as domain]
    [com.repldriven.queenswood.ledger-account.store :as store]

    [com.repldriven.queenswood.balance-query.interface :as balance-query]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.cache.interface :as cache]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]))

(defn- get-policies
  [txn bank-id opts]
  (or (:policies opts)
      (policy/get-effective-policies txn {:bank-id bank-id})))

(defn new-account
  ([txn bank-id currency row]
   (new-account txn bank-id currency row {}))
  ([txn bank-id currency row opts]
   (let-nom>
     [policies (get-policies txn bank-id opts)
      account (domain/new-ledger-account bank-id currency row policies)
      _ (store/save-account txn account)
      _ (when-not (domain/derived (:gl-account-code account))
          (balances/new-balances txn
                                 bank-id
                                 [(domain/opening-balance account)]))]
     account)))

(defn get-account
  [txn bank-id ledger-account-id]
  (store/find-by-id txn bank-id ledger-account-id))

(defn- sub-ledger-sums
  [txn account spec opts]
  (let [{:keys [bank-id currency]} account
        {:keys [product-types balance-status]} spec
        opts (assoc opts :balance-status balance-status)]
    (reduce (fn [acc product-type]
              (let [sum (balance-query/sub-ledger-balance
                         txn
                         bank-id
                         product-type
                         currency
                         opts)]
                (if (error/anomaly? sum)
                  (reduced sum)
                  (merge-with + acc sum))))
            {:credit 0 :debit 0}
            product-types)))

(defn- derived-balance
  [txn account spec opts]
  (let-nom>
    [sums (if (:journal? spec)
            (transactions/sum-legs txn
                                   (:ledger-account-id account)
                                   :balance-type-default
                                   (:balance-status spec)
                                   opts)
            (sub-ledger-sums txn account spec opts))]
    (domain/derived-balance account spec sums)))

(defn- account-balances
  [txn bank-id account]
  (if-let [spec (domain/derived (:gl-account-code account))]
    (let-nom> [balance (derived-balance txn account spec {})]
      [balance])
    (balance-query/list-balances txn bank-id (:ledger-account-id account))))

(defn get-balances
  [txn bank-id account]
  (let-nom>
    [balances (account-balances txn bank-id account)]
    (balance-query/totals balances)))

(defn- posted-balance
  [txn bank-id account]
  (if-let [spec (domain/derived (:gl-account-code account))]
    (derived-balance txn account spec {:isolation :serializable})
    (balance-query/get-balance txn
                               bank-id
                               (:ledger-account-id account)
                               :balance-type-default
                               (:currency account)
                               :balance-status-posted)))

(defn close-account
  ([txn bank-id ledger-account-id]
   (close-account txn bank-id ledger-account-id {}))
  ([txn bank-id ledger-account-id opts]
   (let-nom>
     [policies (get-policies txn bank-id opts)
      account (get-account txn bank-id ledger-account-id)
      balance (posted-balance txn bank-id account)
      closed (domain/close account balance policies)
      _ (store/save-account txn closed)]
     closed)))


(defn list-accounts
  [txn bank-id]
  (store/list-by-bank txn bank-id))

(defn list-accounts-with-balances
  [config bank-id]
  (let-nom>
    [pairs (store/list-by-bank-with-balances config bank-id)]
    (reduce (fn [acc {:keys [account] :as pair}]
              (if-let [spec (domain/derived (:gl-account-code account))]
                (let [balance (derived-balance config account spec {})]
                  (if (error/anomaly? balance)
                    (reduced balance)
                    (conj acc (assoc pair :balances [balance]))))
                (conj acc pair)))
            []
            pairs)))

(defn- cached-ids
  [cache bank-id codes currency]
  (into {}
        (keep (fn [code]
                (when-some [id (cache/lookup cache
                                             [bank-id code currency]
                                             (constantly nil))]
                  [code id])))
        codes))

(defn- load-by-codes
  [txn bank-id codes currency]
  (let [cache (store/cache txn)
        ids (if cache (cached-ids cache bank-id codes currency) {})
        unknown (vec (remove (fn [code] (contains? ids code)) codes))]
    (let-nom>
      [by-id (if (seq ids) (store/find-by-ids txn bank-id (vals ids)) {})
       found (if (seq unknown)
               (store/find-by-codes txn bank-id unknown currency)
               {})]
      (when cache
        (doseq [[code account] found
                :when account]
          (cache/lookup cache
                        [bank-id code currency]
                        (constantly (:ledger-account-id account)))))
      (into found
            (map (fn [[code id]] [code (get by-id id)]))
            ids))))

(defn- find-by-codes
  [txn bank-id codes currency]
  (let-nom>
    [found (if (seq codes)
             (load-by-codes txn bank-id codes currency)
             {})]
    (reduce (fn [acc code]
              (let [account (if-some [account (get found code)]
                              (domain/ensure-open account)
                              (domain/missing-currency-account bank-id
                                                               code
                                                               currency))]
                (if (error/anomaly? account)
                  (reduced account)
                  (conj acc account))))
            []
            codes)))

(defn find-by-code
  [txn bank-id gl-account-code currency]
  (let-nom> [[account] (find-by-codes txn bank-id [gl-account-code] currency)]
    account))

(defn prefetch
  [txn bank-id currency product-types]
  (when-let [cache (store/cache txn)]
    (let [codes (into (set (keep (fn [[code spec]]
                                   (when (domain/posted-to? spec) code))
                                 domain/derived))
                      (keep domain/product-type->control-code)
                      product-types)
          ids (vals (cached-ids cache bank-id codes currency))]
      (when (seq ids) (store/preload-by-ids txn bank-id ids)))))

(defn ensure-controls
  [txn bank-id currency legs]
  (let-nom>
    [_ (find-by-codes txn
                      bank-id
                      (vec (into #{}
                                 (comp (filter domain/fans-out?)
                                       (keep
                                        (fn [leg]
                                          (domain/product-type->control-code
                                           (:product-type leg)))))
                                 legs))
                      currency)]
    legs))

(defn stored-legs
  [txn bank-id currency legs]
  (let-nom>
    [accounts (find-by-codes txn
                             bank-id
                             (vec (keep (fn [[code spec]]
                                          (when (domain/posted-to? spec) code))
                                        domain/derived))
                             currency)
     ids (set (map :ledger-account-id accounts))]
    (into [] (remove (fn [leg] (contains? ids (:account-id leg)))) legs)))
