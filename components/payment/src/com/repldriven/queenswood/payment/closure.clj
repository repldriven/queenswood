(ns com.repldriven.queenswood.payment.closure
  (:require
    [com.repldriven.queenswood.payment.account-limits :as account-limits]
    [com.repldriven.queenswood.payment.domain.checks :as checks]
    [com.repldriven.queenswood.payment.domain.closure :as closure]
    [com.repldriven.queenswood.payment.store :as store]

    [com.repldriven.queenswood.balance-query.interface :as balances-q]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.cash-account-query.interface :as
     cash-accounts]
    [com.repldriven.queenswood.interest.interface :as interest]
    [com.repldriven.queenswood.ledger-account.interface :as
     ledger-accounts]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- post
  "Records `transaction` and applies its legs, its ledger accounts'
  checked first, under `transaction-type` and the bank's `policies`, and
  `account-policies` where an account's version adds limits."
  [txn transaction transaction-type policies account-policies]
  (let [{:keys [bank-id currency legs]} transaction]
    (let-nom>
      [checked-legs (ledger-accounts/accept-legs txn bank-id currency legs)
       recorded (transactions/record-transaction
                 txn
                 (assoc transaction
                        :legs checked-legs
                        :actor closure/platform))
       _ (balances/accept-legs txn
                               bank-id
                               (:legs recorded)
                               transaction-type
                               (utility/assoc-some
                                {:policies (policy/platform-policies
                                            policies)}
                                :account-policies
                                account-policies))]
      recorded)))

(defn- charge-fee
  "Charges closing `account` `amount` against 4100 fee income, where it
  is more than zero."
  [txn account operation-id amount business-day policies]
  (when (pos? amount)
    (let [{:keys [bank-id currency]} account]
      (let-nom>
        [income (ledger-accounts/find-by-code txn
                                              bank-id
                                              :ledger-account-code-fee-income
                                              currency)]
        (post txn
              (closure/fee-transaction account
                                       operation-id
                                       (:ledger-account-id income)
                                       amount
                                       business-day)
              :transaction-type-fee
              policies
              nil)))))

(defn- pay-out
  "Moves `amount` from closing `account` to `nominated` in a `closure`
  transaction, held to what an internal payment into `nominated` is: its
  inbound payment limits, the bank's and its version's, and its version's
  balance limits."
  [txn account operation-id nominated amount business-day policies]
  (let [{:keys [currency]} account
        nominated-id (:account-id nominated)]
    (let-nom>
      [limits (account-limits/get-aggregates nil
                                             txn
                                             policies
                                             nominated
                                             :payment-direction-in
                                             business-day)
       _ (checks/check-account-payment (:policies limits)
                                       nominated-id
                                       :payment-direction-in
                                       amount
                                       currency
                                       (:aggregates limits))]
      (post txn
            (closure/closure-transaction account
                                         operation-id
                                         nominated
                                         amount
                                         business-day)
            :transaction-type-closure
            policies
            (account-limits/version-policies {nominated-id limits})))))

(defn- carry-out
  "Carries out the close `operation` of `account`, inside one transaction:
  settles its accrued interest by the operation's interest term, charges
  its fee, and moves what is left to its nominated account. Returns the
  paid closure, or the anomaly that stopped it, its postings rolled back."
  [txn operation account]
  (let [{:keys [bank-id account-id operation-id nominated-account-id
                closure-interest closure-fee]}
        operation
        today (utility/today)]
    (store/transact
     txn
     (fn [txn]
       (let-nom>
         [policies (policy/get-effective-policies txn
                                                  {:bank-id bank-id
                                                   :account-id account-id})
          nominated (when nominated-account-id
                      (cash-accounts/get-account txn
                                                 bank-id
                                                 nominated-account-id))
          {:keys [paid]} (interest/settle-accrued txn
                                                  {:account account
                                                   :interest closure-interest
                                                   :business-day today
                                                   :policies policies})
          account-balances (balances-q/list-balances txn bank-id account-id)
          settled (closure/default-held account-balances)
          fee (closure/closure-fee closure-fee settled)
          _ (charge-fee txn account operation-id fee today policies)
          held (- settled fee)
          _ (closure/check-held account held)
          transaction (when (and nominated (pos? held))
                        (pay-out txn
                                 account
                                 operation-id
                                 nominated
                                 held
                                 today
                                 policies))
          moved (if transaction held 0)]
         (store/save-closure txn
                             (closure/paid-closure operation
                                                   (:currency account)
                                                   paid
                                                   fee
                                                   moved
                                                   transaction))))
     :payment/close-account
     "Failed to carry out the account's close")))

(defn close-account
  "Carries out the close operation the operation change `data` reports,
  once: a closure already recorded for it is a redelivery, left as it is.
  A close refused, by a limit of its nominated account or by the account
  being overdrawn, is recorded refused with the reason, and nothing it
  posted stands."
  [config data]
  (let [{:keys [bank-id account-id operation-id]} data
        txn (select-keys config [:record-db :record-store :caches])]
    (let-nom>
      [existing (store/find-closure txn bank-id operation-id)
       operation (when-not existing
                   (cash-accounts/find-operation txn bank-id operation-id))
       account (when operation
                 (cash-accounts/get-account txn bank-id account-id))]
      (when account
        (let [result (carry-out txn operation account)]
          (if (error/rejection? result)
            (store/save-closure txn
                                (closure/refused-closure operation
                                                         (:currency account)
                                                         result))
            result))))))
