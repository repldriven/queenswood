(ns com.repldriven.queenswood.ledger-account.core
  (:require
    [com.repldriven.queenswood.ledger-account.domain :as domain]
    [com.repldriven.queenswood.ledger-account.store :as store]

    [com.repldriven.queenswood.balance-query.interface :as balance-query]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.policy.interface :as policy]

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
      _ (when-not (domain/control-code->product-type
                   (:gl-account-code account))
          (balances/new-balances txn
                                 bank-id
                                 [(domain/opening-balance account)]))]
     account)))

(defn get-account
  [txn bank-id ledger-account-id]
  (store/find-by-id txn bank-id ledger-account-id))

(defn- control-balance
  [txn account product-type opts]
  (let-nom>
    [sums (balance-query/sub-ledger-balance txn
                                            (:bank-id account)
                                            product-type
                                            (:currency account)
                                            opts)]
    (domain/derived-balance account sums)))

(defn- account-balances
  [txn bank-id account]
  (if-let [product-type (domain/control-code->product-type
                         (:gl-account-code account))]
    (let-nom> [balance (control-balance txn account product-type {})]
      [balance])
    (balance-query/list-balances txn bank-id (:ledger-account-id account))))

(defn get-balances
  [txn bank-id account]
  (let-nom>
    [balances (account-balances txn bank-id account)]
    (balance-query/totals balances)))

(defn- posted-balance
  [txn bank-id account]
  (if-let [product-type (domain/control-code->product-type
                         (:gl-account-code account))]
    (control-balance txn account product-type {:isolation :serializable})
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
              (if-let [product-type (domain/control-code->product-type
                                     (:gl-account-code account))]
                (let [balance (control-balance config account product-type {})]
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
