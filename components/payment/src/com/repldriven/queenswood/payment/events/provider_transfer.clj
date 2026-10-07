(ns com.repldriven.queenswood.payment.events.provider-transfer
  (:require
    [com.repldriven.queenswood.payment.domain.provider-transfer :as
     provider-transfer]
    [com.repldriven.queenswood.payment.provider :as provider]
    [com.repldriven.queenswood.payment.store :as store]

    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- bank-accounts
  "The bank's 1100 and its own-funds cash account in `currency`, as
  `{:cash-at-correspondent-id :own-funds}`. Neither changes once the bank
  exists, so they are cached."
  [config txn bank-id currency]
  (provider/cached
   config
   [:bank-accounts bank-id currency]
   (fn []
     (let-nom>
       [cash (ledger-accounts/find-by-code
              txn
              bank-id
              :gl-account-code-cash-at-correspondent
              currency)
        house (cash-accounts/house-account txn bank-id currency)]
       {:cash-at-correspondent-id (:ledger-account-id cash)
        :own-funds (:account-id house)}))))

(defn- settled-party
  "`account`'s mirror party where it can no longer change — the account
  has a provider account, or is the bank's own funds — else nil."
  [account own-funds]
  (let [{:keys [account-id provider-account-id]} account]
    (when (or provider-account-id (= account-id own-funds))
      (provider-transfer/mirror-party account own-funds))))

(defn- mirror-parties
  "The mirror party of each of `account-ids` that is a cash account. An
  id that is not one, such as a ledger account's, never becomes one, and
  a settled party never changes, so both are cached; the rest are read
  in one round trip."
  [config txn bank-id account-ids own-funds]
  (let [k (fn [id] [:mirror-party bank-id id])
        known (into {}
                    (keep (fn [id]
                            (when-some [p (provider/cached config
                                                           (k id)
                                                           (constantly nil))]
                              [id p])))
                    account-ids)
        unknown (remove (fn [id] (contains? known id)) account-ids)]
    (if (empty? unknown)
      (into {} (remove (fn [[_ p]] (= ::not-cash p))) known)
      (let-nom> [accounts (cash-accounts/find-accounts txn bank-id unknown)]
        (doseq [id unknown]
          (let [account (get accounts id)
                settled
                (if account (settled-party account own-funds) ::not-cash)]
            (when settled
              (provider/cached config (k id) (constantly settled)))))
        (into (into {} (remove (fn [[_ p]] (= ::not-cash p))) known)
              (map (fn [[id account]]
                     [id (provider-transfer/mirror-party account own-funds)]))
              accounts)))))

(defn- mirror-context
  "What `provider-transfer/provider-transfers` needs to know about the
  accounts a posting touched: which are cash accounts and whose provider
  account holds each one's money, which is 1100, the account the scheme
  moved the money through, and the bank's own funds."
  [config txn posted]
  (let [{:keys [bank-id currency legs scheme-account-id]} posted]
    (let-nom>
      [accounts (bank-accounts config txn bank-id currency)
       {:keys [cash-at-correspondent-id own-funds]} accounts
       parties (mirror-parties config
                               txn
                               bank-id
                               (distinct (keep identity
                                               (conj (mapv :account-id legs)
                                                     scheme-account-id)))
                               own-funds)]
      {:cash-accounts parties
       :cash-at-correspondent-id cash-at-correspondent-id
       :scheme-account-id (get parties scheme-account-id)
       :own-funds own-funds})))

(defn- record-transfers
  "The posting's provider transfers, recorded pending in one
  transaction: those already recorded for it where this is a
  redelivery, or new ones."
  [config posted]
  (store/transact
   config
   (fn [txn]
     (let-nom> [existing (store/transfers-for-transaction
                          txn
                          (:transaction-id posted))]
       (if (seq existing)
         existing
         (let-nom> [ctx (mirror-context config txn posted)]
           (let [transfers
                 (mapv (fn [t]
                         (provider-transfer/new-provider-transfer posted t))
                       (provider-transfer/provider-transfers posted ctx))]
             (let-nom> [_ (reduce (fn [_ t]
                                    (let [res (store/save-transfer txn t)]
                                      (when (error/anomaly? res)
                                        (reduced res))))
                                  nil
                                  transfers)]
               transfers))))))
   :payment/mirror
   "Failed to record provider transfers"))

(defn- send-transfer
  [config transfer]
  (let [{:keys [bank-id transaction-id debtor-account-id]} transfer]
    (provider/send-command config
                           bank-id
                           "transfer-between-accounts"
                           transaction-id
                           (utility/assoc-some
                            (select-keys transfer
                                         [:transfer-id :bank-id
                                          :transaction-id :creditor-account-id
                                          :amount :currency])
                            :debtor-account-id
                            debtor-account-id))))

(defn mirror-posted
  "Where the provider holds a balance for each account, record and send
  the transfers that make the provider accounts hold what a posted
  transaction left in the ledger, naming each cash account for the
  adapter to resolve. A redelivery sends again those still pending,
  which the adapter takes as the transfers it already has. A posting
  that mirrors nothing whatever its accounts' parties, as an outbound's
  reservation and the scheme's own settlement, reads and records
  nothing."
  [config posted]
  (let [{:keys [bank-id currency]} posted]
    (let-nom> [declaration (provider/declaration config config bank-id)]
      (when (= "per-account" (:balances declaration))
        (let-nom> [{:keys [cash-at-correspondent-id]}
                   (bank-accounts config config bank-id currency)]
          (when-not (provider-transfer/mirrors-nothing?
                     posted
                     cash-at-correspondent-id)
            (let-nom> [transfers (record-transfers config posted)]
              (reduce (fn [_ t]
                        (if (= :provider-transfer-status-pending (:status t))
                          (let [res (send-transfer config t)]
                            (if (error/anomaly? res) (reduced res) nil))
                          nil))
                      nil
                      transfers))))))))

(defn- finish-transfer
  [config data status reason]
  (let [{:keys [bank-id transfer-id]} data]
    (store/transact
     config
     (fn [txn]
       (let-nom> [transfer (store/get-transfer txn bank-id transfer-id)]
         (if (nil? transfer)
           (error/fail :payment/unknown-transfer
                       {:message "No provider transfer has this id"
                        :bank-id bank-id
                        :transfer-id transfer-id})
           (if-let [finished
                    (provider-transfer/transfer-outcome transfer status reason)]
             (let-nom> [_ (store/save-transfer txn finished)] finished)
             transfer))))
     :payment/finish-transfer
     "Failed to record a provider transfer's outcome")))

(defn complete-transfer
  [config data]
  (finish-transfer config data :provider-transfer-status-completed nil))

(defn fail-transfer
  "A transfer the provider did not make leaves the ledger as it is: the
  customer's payment stands, and the bank reconciles the provider
  accounts from the ERROR this logs."
  [config data]
  (let [{:keys [bank-id transfer-id reason]} data]
    (log/error "A provider transfer failed, leaving the provider off the ledger"
               {:bank-id bank-id :transfer-id transfer-id :reason reason})
    (finish-transfer config data :provider-transfer-status-failed reason)))
