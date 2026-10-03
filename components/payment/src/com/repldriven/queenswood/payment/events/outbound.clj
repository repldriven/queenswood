(ns com.repldriven.queenswood.payment.events.outbound
  (:require
    [com.repldriven.queenswood.payment.domain.outbound :as outbound]
    [com.repldriven.queenswood.payment.store :as store]

    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.payment-query.interface :as q]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]))

(defn- record-settlement-leg
  "Settle an outbound: clear the debtor's pending-outgoing reservation and
  post the real outflow, draining 1200 → 1100. The debtor's posted debit is
  a sub-ledger leg, so `ensure-controls` checks the deposit control it
  rolls into."
  [txn payment]
  (let [{:keys [bank-id debtor-account-id currency]} payment]
    (let-nom>
      [pending (ledger-accounts/find-by-code
                txn
                bank-id
                :gl-account-code-pending-outbound
                currency)
       cash (ledger-accounts/find-by-code
             txn
             bank-id
             :gl-account-code-cash-at-correspondent
             currency)
       debtor-account (cash-accounts/get-account
                       txn
                       bank-id
                       debtor-account-id)
       tx (outbound/outbound-settlement->transaction
           payment
           debtor-account
           (:ledger-account-id pending)
           (:ledger-account-id cash))
       checked-legs (ledger-accounts/ensure-controls
                     txn
                     bank-id
                     currency
                     (:legs tx))
       recorded (transactions/record-transaction
                 txn
                 (assoc tx :legs checked-legs))
       {:keys [transaction-type legs]} recorded
       _ (balances/apply-legs txn bank-id legs transaction-type)]
      recorded)))

(defn settle-outbound
  [config data]
  (let [{payment-id :end-to-end-id} data]
    (store/transact
     config
     (fn [txn]
       (let-nom> [payment (q/get-outbound-payment txn payment-id)]
         (cond
          (nil? payment)
          (error/fail
           :payment/settle-outbound
           {:message
            "Failed to find corresponding outbound payment for settlement"
            :payment-id payment-id})

          (= :outbound-payment-status-completed (:payment-status payment))
          (do (log/infof "Outbound payment settlement already completed: %s"
                         payment-id)
              payment)

          (not (outbound/settleable-outbound? payment))
          (do (log/errorf
               "Outbound payment settlement skipped, not settleable: %s"
               {:payment-id payment-id
                :payment-status (:payment-status payment)
                :failure-reason-code (:failure-reason-code payment)
                :scheme-transaction-id (:scheme-transaction-id data)})
              payment)

          :else
          (let-nom>
            [completed (outbound/completed-outbound-payment payment)
             _ (store/save-outbound-payment
                txn
                completed
                {:change-kind :outbound-payment-change-kind-settle
                 :status-before (:payment-status payment)})
             _ (record-settlement-leg txn payment)]
            (log/infof "Outbound payment settlement now completed: %s"
                       {:payment-id payment-id})
            completed))))
     :payment/settle-outbound
     "Failed to settle outbound payment")))

(defn hold-outbound
  "Mark an outbound payment held while the scheme screens it. The money
  stays parked in 1200 pending-outbound, so there is no balance move —
  only the payment status flips pending → held."
  [config data]
  (let [{payment-id :end-to-end-id} data]
    (store/transact
     config
     (fn [txn]
       (let-nom> [payment (q/get-outbound-payment txn payment-id)]
         (cond
          (nil? payment)
          (error/fail
           :payment/hold-outbound
           {:message "Failed to find corresponding outbound payment to hold"
            :payment-id payment-id})

          (not= :outbound-payment-status-pending (:payment-status payment))
          (do (log/infof "Outbound payment hold ignored, not pending: %s"
                         {:payment-id payment-id
                          :payment-status (:payment-status payment)})
              payment)

          :else
          (let-nom>
            [held (outbound/held-outbound-payment payment)
             _ (store/save-outbound-payment
                txn
                held
                {:change-kind :outbound-payment-change-kind-hold
                 :status-before (:payment-status payment)})]
            (log/infof "Outbound payment now held: %s" {:payment-id payment-id})
            held))))
     :payment/hold-outbound
     "Failed to hold outbound payment")))

(defn- record-reversal-leg
  "DEBIT 1200 pending-outbound / CREDIT debtor — reverse the submission of
  an outbound payment the scheme declined or returned. The debtor leg is a
  sub-ledger account, so `ensure-controls` checks its control."
  [txn payment]
  (let [{:keys [bank-id debtor-account-id currency]} payment]
    (let-nom>
      [pending (ledger-accounts/find-by-code
                txn
                bank-id
                :gl-account-code-pending-outbound
                currency)
       debtor-account (cash-accounts/get-account
                       txn
                       bank-id
                       debtor-account-id)
       tx (outbound/outbound-reversal->transaction
           payment
           debtor-account
           (:ledger-account-id pending))
       checked-legs (ledger-accounts/ensure-controls
                     txn
                     bank-id
                     currency
                     (:legs tx))
       recorded (transactions/record-transaction
                 txn
                 (assoc tx :legs checked-legs))
       {:keys [transaction-type legs]} recorded
       _ (balances/apply-legs txn bank-id legs transaction-type)]
      recorded)))

(defn reject-outbound
  "Process an outbound `transaction-rejected` event. Reverses the in-flight
  payment (DEBIT 1200 / CREDIT debtor) and flips the OutboundPayment to
  failed with the failure the event reports. Pending and held
  payments are reversible; an already-failed payment is an idempotent
  no-op; a completed or returned payment is no longer in flight and
  cannot be reversed here."
  [config data]
  (let [{payment-id :end-to-end-id} data
        {:keys [reason-code]} data]
    (store/transact
     config
     (fn [txn]
       (let-nom> [payment (q/get-outbound-payment txn payment-id)]
         (cond
          (nil? payment)
          (error/fail
           :payment/reject-outbound
           {:message "Failed to find corresponding outbound payment to reject"
            :payment-id payment-id})

          (= :outbound-payment-status-failed (:payment-status payment))
          (do (log/infof "Outbound payment rejection already processed: %s"
                         {:payment-id payment-id})
              payment)

          (not (outbound/settleable-outbound? payment))
          (error/fail
           :payment/reject-outbound
           {:message "Cannot reverse an outbound payment no longer in flight"
            :payment-id payment-id
            :payment-status (:payment-status payment)})

          :else
          (let-nom>
            [failed (outbound/failed-outbound-payment payment data)
             _ (store/save-outbound-payment
                txn
                failed
                {:change-kind :outbound-payment-change-kind-fail
                 :status-before (:payment-status payment)})
             _ (record-reversal-leg txn payment)]
            (log/infof "Outbound payment rejected and reversed: %s"
                       {:payment-id payment-id
                        :reason-code reason-code})
            failed))))
     :payment/reject-outbound
     "Failed to reject outbound payment")))

(defn- record-return-leg
  "DEBIT 1100 / CREDIT debtor — bring a returned outbound's money back. The
  debtor leg is a sub-ledger account, so `ensure-controls` checks its
  control."
  [txn payment amount]
  (let [{:keys [bank-id debtor-account-id currency]} payment]
    (let-nom>
      [cash (ledger-accounts/find-by-code
             txn
             bank-id
             :gl-account-code-cash-at-correspondent
             currency)
       debtor-account (cash-accounts/get-account
                       txn
                       bank-id
                       debtor-account-id)
       tx (outbound/outbound-return->transaction
           payment
           debtor-account
           (:ledger-account-id cash)
           amount)
       checked-legs (ledger-accounts/ensure-controls
                     txn
                     bank-id
                     currency
                     (:legs tx))
       recorded (transactions/record-transaction
                 txn
                 (assoc tx :legs checked-legs))
       {:keys [transaction-type legs]} recorded
       _ (balances/apply-legs txn bank-id legs transaction-type)]
      recorded)))

(defn return-outbound
  [config data]
  (let [{payment-id :end-to-end-id} data
        {:keys [amount reason-code]} data]
    (store/transact
     config
     (fn [txn]
       (let-nom> [payment (q/get-outbound-payment txn payment-id)]
         (cond
          (nil? payment)
          (error/fail :payment/return-outbound
                      {:message
                       "Failed to find corresponding outbound payment to return"
                       :payment-id payment-id})

          (= :outbound-payment-status-returned (:payment-status payment))
          (do (log/infof "Outbound payment return already processed: %s"
                         {:payment-id payment-id})
              payment)

          (not= :outbound-payment-status-completed (:payment-status payment))
          (error/fail :payment/return-outbound
                      {:message
                       "Cannot return an outbound payment not completed"
                       :payment-id payment-id
                       :payment-status (:payment-status payment)})

          :else
          (let-nom>
            [returned (outbound/returned-outbound-payment payment data)
             _ (store/save-outbound-payment
                txn
                returned
                {:change-kind :outbound-payment-change-kind-return
                 :status-before (:payment-status payment)})
             _ (record-return-leg txn payment amount)]
            (log/infof "Outbound payment returned: %s"
                       {:payment-id payment-id
                        :reason-code reason-code})
            returned))))
     :payment/return-outbound
     "Failed to return outbound payment")))
