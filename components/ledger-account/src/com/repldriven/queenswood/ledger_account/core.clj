(ns com.repldriven.queenswood.ledger-account.core
  (:require
    [com.repldriven.queenswood.ledger-account.domain :as domain]
    [com.repldriven.queenswood.ledger-account.store :as store]

    [com.repldriven.queenswood.balance-query.interface :as balance-query]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as transactions]

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

(defn find-by-code
  [txn bank-id gl-account-code currency]
  (let-nom>
    [account (store/find-by-code txn bank-id gl-account-code currency)]
    (if account
      (domain/ensure-open account)
      (domain/missing-currency-account bank-id gl-account-code currency))))

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

(defn ensure-controls
  [txn bank-id currency legs]
  (let-nom>
    [_ (reduce (fn [_ code]
                 (let [control (find-by-code txn bank-id code currency)]
                   (when (error/anomaly? control) (reduced control))))
               nil
               (into #{}
                     (comp (filter domain/fans-out?)
                           (keep (fn [leg]
                                   (domain/product-type->control-code
                                    (:product-type leg)))))
                     legs))]
    legs))

(defn stored-legs
  [txn bank-id currency legs]
  (let-nom>
    [ids (reduce (fn [ids code]
                   (let [account (find-by-code txn bank-id code currency)]
                     (if (error/anomaly? account)
                       (reduced account)
                       (conj ids (:ledger-account-id account)))))
                 #{}
                 (keep (fn [[code spec]]
                         (when (domain/posted-to? spec) code))
                       domain/derived))]
    (into [] (remove (fn [leg] (contains? ids (:account-id leg)))) legs)))
