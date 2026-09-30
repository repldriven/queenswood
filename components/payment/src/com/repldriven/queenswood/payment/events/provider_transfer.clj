(ns com.repldriven.queenswood.payment.events.provider-transfer
  (:require
    [com.repldriven.queenswood.payment.domain.provider-transfer :as
     provider-transfer]
    [com.repldriven.queenswood.payment.provider :as provider]
    [com.repldriven.queenswood.payment.store :as store]

    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]

    [com.repldriven.mono.avro.interface :as avro]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.message-bus.interface :as message-bus]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- mirror-context
  "What `provider-transfer/provider-transfers` needs to know about the
  accounts a posting touched: which are cash accounts and whose provider
  account holds each one's money, which is 1100, the account the scheme
  moved the money through, and the bank's own funds."
  [txn posted]
  (let [{:keys [bank-id currency legs scheme-account-id]} posted]
    (let-nom>
      [cash (ledger-accounts/find-by-code txn
                                          bank-id
                                          :gl-account-code-cash-at-correspondent
                                          currency)
       house (cash-accounts/house-account txn bank-id currency)
       own-funds (:account-id house)
       parties (reduce (fn [acc account-id]
                         (let [account (cash-accounts/find-account txn
                                                                   bank-id
                                                                   account-id)]
                           (cond
                            (error/anomaly? account)
                            (reduced account)

                            account
                            (assoc acc
                                   account-id
                                   (provider-transfer/mirror-party account
                                                                   own-funds))

                            :else
                            acc)))
                       {}
                       (distinct (keep identity
                                       (conj (mapv :account-id legs)
                                             scheme-account-id))))]
      {:cash-accounts parties
       :cash-at-correspondent-id (:ledger-account-id cash)
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
         (let-nom> [ctx (mirror-context txn posted)]
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

(defn- provider-account
  [config bank-id account-id]
  (when account-id
    (let [account (cash-accounts/find-account config bank-id account-id)]
      (when-not (error/anomaly? account) (:provider-account-id account)))))

(defn send-transfer
  "Send a pending transfer as `transfer-between-accounts`, naming the
  provider accounts its cash accounts now have. One whose provider
  account is not yet opened is left for the sweep to send."
  [config transfer]
  (let [{:keys [bus schemas]} config
        {:keys [bank-id transaction-id debtor-account-id creditor-account-id]}
        transfer
        debtor (provider-account config bank-id debtor-account-id)
        creditor (provider-account config bank-id creditor-account-id)]
    (if (or (nil? creditor) (and debtor-account-id (nil? debtor)))
      (log/info "Provider transfer waits for a provider account"
                {:transfer-id (:transfer-id transfer)})
      (let-nom> [channel
                 (provider/payment-command-channel config config bank-id)
                 payload (avro/serialize
                          (get schemas "transfer-between-accounts")
                          (utility/assoc-some
                           (assoc (select-keys transfer
                                               [:transfer-id :bank-id
                                                :transaction-id :amount
                                                :currency])
                                  :creditor-provider-account-id
                                  creditor)
                           :debtor-provider-account-id
                           debtor))]
        (message-bus/send bus
                          channel
                          {:command "transfer-between-accounts"
                           :id (str (utility/uuidv7))
                           :correlation-id (str (utility/uuidv7))
                           :causation-id transaction-id
                           :traceparent (telemetry/inject-traceparent)
                           :payload payload})))))

(defn mirror-posted
  "Where the provider holds a balance for each account, record and send
  the transfers that make the provider accounts hold what a posted
  transaction left in the ledger. A redelivery sends again those still
  pending, which the adapter takes as the transfers it already has."
  [config posted]
  (let-nom> [declaration (provider/declaration config config (:bank-id posted))]
    (when (= "per-account" (:balances declaration))
      (let-nom> [transfers (record-transfers config posted)]
        (reduce (fn [_ t]
                  (if (= :provider-transfer-status-pending (:status t))
                    (let [res (send-transfer config t)]
                      (if (error/anomaly? res) (reduced res) nil))
                    nil))
                nil
                transfers)))))

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
