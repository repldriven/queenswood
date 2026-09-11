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
      _ (balances/new-balances txn
                               bank-id
                               [(domain/opening-balance account)])]
     account)))

(defn get-account
  [txn bank-id ledger-account-id]
  (store/find-by-id txn bank-id ledger-account-id))

(defn close-account
  ([txn bank-id ledger-account-id]
   (close-account txn bank-id ledger-account-id {}))
  ([txn bank-id ledger-account-id opts]
   (let-nom>
     [policies (get-policies txn bank-id opts)
      account (get-account txn bank-id ledger-account-id)
      balance (balance-query/get-balance txn
                                         bank-id
                                         ledger-account-id
                                         :balance-type-default
                                         (:currency account)
                                         :balance-status-posted)
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

(defn- control-code
  "The control gl-code `leg` rolls up into: an interest-accrued bucket
  goes to 2400 (by balance-type) ahead of the product-type deposit
  control, so a savings account's accrued interest reconciles to 2400
  rather than 2200. Nil if the leg has no control counterpart."
  [leg]
  (or (get domain/balance-type->control-code (:balance-type leg))
      (get domain/product-type->control-code (:product-type leg))))

(defn- control-leg
  "If `leg` fans out, resolve its control ledger account in `currency`
  and return a same-side mirror leg targeting the control's
  default-posted bucket, tagged `:control` so the double-entry balance
  check skips the roll-up. Nil for legs that don't fan out and legs with
  no resolvable control code; the `:gl/missing-currency-account`
  rejection for a leg whose control is not seeded in `currency`."
  [txn bank-id currency leg]
  (when (domain/fans-out? leg)
    (when-let [code (control-code leg)]
      (let-nom>
        [control (find-by-code txn bank-id code currency)]
        {:account-id (:ledger-account-id control)
         :balance-type :balance-type-default
         :balance-status :balance-status-posted
         :side (:side leg)
         :amount (:amount leg)
         :control true}))))

(defn add-control-legs
  [txn bank-id currency legs]
  (reduce (fn [acc leg]
            (let [extra (control-leg txn bank-id currency leg)]
              (cond
               (error/anomaly? extra)
               (reduced extra)

               extra
               (conj acc leg extra)

               :else
               (conj acc leg))))
          []
          legs))
