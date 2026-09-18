(ns com.repldriven.queenswood.payment.events
  (:require
    [com.repldriven.queenswood.payment.domain :as domain]
    [com.repldriven.queenswood.payment.store :as store]

    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.bank-query.interface :as banks]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as
     ledger-accounts]
    [com.repldriven.queenswood.payment-query.interface :as q]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

;; Every handler opens one FDB transaction (`store/transact config`) and
;; threads that `txn` through every read, record, and store call, so the
;; whole event — dedup lookup, status flip, ledger posting, balance apply
;; — commits or rolls back as a unit. `fdb/transact` reuses an open `txn`
;; (composition) and rolls the transaction back if the body returns an
;; anomaly. The `record-*` helpers take `txn` (plus a precomputed
;; `business-day`, since the raw txn doesn't carry `:business-day-cutoff`).

(defn- check-debit-credit-code
  [debit-credit-code]
  (when (not= :debit-credit-code-credit debit-credit-code)
    (error/fail
     :payment/settle-inbound
     {:message "Inbound payment settlement for non-credit is not permissible"
      :debit-credit-code debit-credit-code})))

(defn- bban->sort-code
  [bban]
  (when (and bban (>= (count bban) 6)) (subs bban 0 6)))

(defn- resolve-suspense-bank
  "The bank owning an unmatched inbound's BBAN, by its sort code. A sort code
  that matches no bank is genuinely foreign and fails."
  [txn data]
  (let [{:keys [creditor-bban]} data
        sort-code (bban->sort-code creditor-bban)]
    (let-nom>
      [bank (banks/get-bank-by-sort-code txn sort-code)
       _ (when (nil? bank)
           (error/fail :payment/no-bank-for-sort-code
                       {:message "No bank owns the inbound BBAN's sort code"
                        :bban creditor-bban
                        :sort-code sort-code}))]
      (:bank-id bank))))

(defn- post-to-suspense
  [txn data bank-id]
  (let [{:keys [currency]} data]
    (let-nom>
      [cash (ledger-accounts/find-by-code
             txn
             bank-id
             :gl-account-code-cash-at-correspondent
             currency)
       suspense (ledger-accounts/find-by-code
                 txn
                 bank-id
                 :gl-account-code-suspense
                 currency)
       transaction (domain/inbound-suspense->transaction
                    data
                    bank-id
                    (:ledger-account-id cash)
                    (:ledger-account-id suspense))
       recorded (transactions/record-transaction txn transaction)
       {:keys [transaction-type legs]} recorded
       _ (balances/apply-legs txn bank-id legs transaction-type)]
      recorded)))

(defn- park-in-suspense
  "Park an inbound in `bank-id`'s 2500 suspense and persist a `suspended`
  InboundPayment for later reconciliation."
  [txn data bank-id business-day]
  (let-nom>
    [recorded (post-to-suspense txn data bank-id)
     {:keys [transaction-id]} recorded
     payment (domain/suspended-inbound-payment data
                                               bank-id
                                               business-day
                                               transaction-id)
     _ (store/save-inbound-payment txn payment)]
    payment))

(defn- record-inbound-settlement
  [txn data account business-day]
  (let [{:keys [account-id bank-id]} account
        {:keys [currency]} data]
    (let-nom>
      [cash (ledger-accounts/find-by-code
             txn
             bank-id
             :gl-account-code-cash-at-correspondent
             currency)
       policies (policy/get-effective-policies
                 txn
                 {:bank-id bank-id})
       today-count (q/count-inbound-by-org-business-day
                    txn
                    bank-id
                    business-day)]
      (let [aggregates {:inbound-payment
                        {#{:bank-id :business-day} today-count}}
            transaction (domain/inbound-payment->transaction
                         data
                         account
                         (:ledger-account-id cash)
                         policies
                         aggregates)]
        (if (domain/refused? transaction)
          (do (log/infof "Inbound settlement refused, parked in suspense: %s"
                         {:kind (error/kind transaction)
                          :account-id account-id})
              (park-in-suspense txn data bank-id business-day))
          (let-nom>
            [_ transaction
             expanded-legs (ledger-accounts/add-control-legs
                            txn
                            bank-id
                            currency
                            (:legs transaction))
             transaction+legs (transactions/record-transaction
                               txn
                               (assoc transaction :legs expanded-legs))
             {:keys [transaction-id transaction-type legs]} transaction+legs
             _ (balances/apply-legs txn bank-id legs transaction-type)
             payment (domain/new-inbound-payment data
                                                 account-id
                                                 bank-id
                                                 business-day
                                                 transaction-id)
             _ (store/save-inbound-payment txn payment)]
            payment))))))

(defn- suspend-held
  [txn data held]
  (let [{:keys [bank-id]} held
        {:keys [scheme-transaction-id]} data]
    (let-nom>
      [recorded (post-to-suspense txn data bank-id)
       {:keys [transaction-id]} recorded
       suspended (domain/suspended-from-held held
                                             scheme-transaction-id
                                             transaction-id)
       _ (store/save-inbound-payment txn suspended)]
      suspended)))

(defn- record-inbound-release
  "Release a held inbound once it passes the checks a settlement runs, with
  today's count excluding the held record itself: post DEBIT 1100 / CREDIT
  creditor and transition the held record to `settled`. A release the checks
  refuse is parked in suspense and the held record becomes `suspended`."
  [txn data account held business-day]
  (let [{:keys [account-id bank-id]} account
        {:keys [scheme-transaction-id]} data
        {:keys [currency]} held]
    (let-nom>
      [cash (ledger-accounts/find-by-code
             txn
             bank-id
             :gl-account-code-cash-at-correspondent
             currency)
       policies (policy/get-effective-policies
                 txn
                 {:bank-id bank-id})
       today-count (q/count-inbound-by-org-business-day
                    txn
                    bank-id
                    business-day)]
      (let [aggregates {:inbound-payment
                        {#{:bank-id :business-day}
                         (domain/release-count today-count held business-day)}}
            transaction (domain/inbound-release->transaction
                         held
                         account
                         (:ledger-account-id cash)
                         policies
                         aggregates)]
        (if (domain/refused? transaction)
          (do (log/infof "Inbound release refused, parked in suspense: %s"
                         {:kind (error/kind transaction)
                          :account-id account-id})
              (suspend-held txn data held))
          (let-nom>
            [_ transaction
             expanded-legs (ledger-accounts/add-control-legs
                            txn
                            bank-id
                            currency
                            (:legs transaction))
             recorded (transactions/record-transaction
                       txn
                       (assoc transaction :legs expanded-legs))
             {:keys [transaction-id transaction-type legs]} recorded
             _ (balances/apply-legs txn bank-id legs transaction-type)
             released (domain/settled-from-held held
                                                scheme-transaction-id
                                                transaction-id)
             _ (store/save-inbound-payment txn released)]
            released))))))

(defn settle-inbound
  "Settle an inbound ClearBank credit against the creditor resolved by
  BBAN. A creditor that is not opened — suspended, closing, closed, or
  still opening — is parked in 2500 suspense rather than credited, as
  an unmatched BBAN is; a held record for it, if any, stays `held`."
  [config data]
  (let [{:keys [debit-credit-code creditor-bban
                scheme-transaction-id end-to-end-id amount]}
        data
        business-day (domain/current-business-day
                      (utility/now)
                      (:business-day-cutoff config))]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [_ (check-debit-credit-code debit-credit-code)
          account (cash-accounts/get-account-by-bban txn creditor-bban)
          settled (q/get-inbound-payment txn scheme-transaction-id)
          held (when account
                 (q/find-open-hold txn
                                   end-to-end-id
                                   (:account-id account)
                                   amount))]
         (cond
          settled
          (do (log/infof "Inbound payment settlement already processed: %s"
                         scheme-transaction-id)
              settled)

          ;; The BBAN resolves, but the account cannot take a credit —
          ;; park the funds in suspense as an unmatched BBAN is, so the
          ;; receipt stays recoverable.
          (and account (not (domain/operable? account)))
          (do (log/infof "Inbound settlement to a non-operable account: %s"
                         {:account-id (:account-id account)
                          :account-status (:account-status account)})
              (park-in-suspense txn data (:bank-id account) business-day))

          ;; Release of a previously-held inbound — settle it to the
          ;; account and flip the held record to settled.
          held
          (record-inbound-release txn data account held business-day)

          ;; No account matches the BBAN — park the funds in 2500 suspense
          ;; rather than losing the receipt (it stays recoverable).
          (nil? account)
          (let-nom> [bank-id (resolve-suspense-bank txn data)]
            (park-in-suspense txn data bank-id business-day))

          :else
          (record-inbound-settlement txn data account business-day))))
     :payment/settle-inbound
     "Failed to settle inbound payment")))

(defn- record-settlement-leg
  "Settle an outbound: clear the debtor's pending-outgoing reservation and
  post the real outflow, draining 1200 → 1100. The debtor's posted debit is
  a sub-ledger leg, so route through `add-control-legs` to fan it up to the
  deposit control."
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
       tx (domain/outbound-settlement->transaction
           payment
           debtor-account
           (:ledger-account-id pending)
           (:ledger-account-id cash))
       expanded-legs (ledger-accounts/add-control-legs
                      txn
                      bank-id
                      currency
                      (:legs tx))
       recorded (transactions/record-transaction
                 txn
                 (assoc tx :legs expanded-legs))
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

          (not (domain/settleable-outbound? payment))
          (do (log/errorf
               "Outbound payment settlement skipped, not settleable: %s"
               {:payment-id payment-id
                :payment-status (:payment-status payment)
                :cancellation-code (:cancellation-code payment)
                :scheme-transaction-id (:scheme-transaction-id data)})
              payment)

          :else
          (let-nom>
            [completed (domain/completed-outbound-payment payment)
             _ (store/save-outbound-payment txn completed)
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
            [held (domain/held-outbound-payment payment)
             _ (store/save-outbound-payment txn held)]
            (log/infof "Outbound payment now held: %s" {:payment-id payment-id})
            held))))
     :payment/hold-outbound
     "Failed to hold outbound payment")))

(defn hold-inbound
  "An inbound ClearBank is holding for screening. Record it `held` (creditor
  resolved by BBAN); no money moves — the funds are held at ClearBank, not
  ours yet. Idempotent on an open hold for the same end-to-end id, creditor
  and amount; a held to an unmatched BBAN, or to a creditor that is not
  opened, is logged and ignored — the settle that follows finds no held
  record and parks in suspense."
  [config data]
  (let [{:keys [creditor-bban end-to-end-id amount]} data
        business-day (domain/current-business-day
                      (utility/now)
                      (:business-day-cutoff config))]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [account (cash-accounts/get-account-by-bban txn creditor-bban)
          existing (when account
                     (q/find-open-hold txn
                                       end-to-end-id
                                       (:account-id account)
                                       amount))]
         (cond
          existing
          (do (log/infof "Inbound hold already recorded: %s" end-to-end-id)
              existing)

          (and account (not (domain/operable? account)))
          (do (log/infof "Inbound held for a non-operable account, ignored: %s"
                         {:account-id (:account-id account)
                          :account-status (:account-status account)})
              data)

          (nil? account)
          (do (log/infof "Inbound held for unmatched BBAN, ignored: %s"
                         {:bban creditor-bban})
              data)

          :else
          (let [{:keys [account-id bank-id]} account
                payment (domain/held-inbound-payment data
                                                     account-id
                                                     bank-id
                                                     business-day)]
            (let-nom> [_ (store/save-inbound-payment txn payment)]
              (log/infof "Inbound now held: %s" {:end-to-end-id end-to-end-id})
              payment)))))
     :payment/hold-inbound
     "Failed to hold inbound payment")))

(defn- find-hold-to-return
  [txn data]
  (let [{:keys [creditor-bban end-to-end-id]} data]
    (if creditor-bban
      (let-nom>
        [account (cash-accounts/get-account-by-bban txn creditor-bban)]
        (when account
          (q/find-open-hold txn end-to-end-id (:account-id account) nil)))
      (let-nom>
        [holds (q/find-open-holds txn end-to-end-id)]
        (domain/select-hold-to-return holds end-to-end-id)))))

(defn return-inbound
  "An inbound held transaction ClearBank declined — the funds returned to
  the remitter, so nothing posts on our books. Transition the matching held
  record to `returned`. Idempotent / no-op when there's no open held."
  [config data]
  (let [{:keys [end-to-end-id]} data]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [held (find-hold-to-return txn data)]
         (if (nil? held)
           (do (log/infof "Inbound return with no held inbound, ignored: %s"
                          end-to-end-id)
               data)
           (let-nom> [returned (domain/returned-inbound-payment held)
                      _ (store/save-inbound-payment txn returned)]
             (log/infof "Inbound held transaction returned: %s"
                        {:end-to-end-id end-to-end-id})
             returned))))
     :payment/return-inbound
     "Failed to return inbound payment")))

(defn- record-reversal-leg
  "DEBIT 1200 pending-outbound / CREDIT debtor — reverse the submission of
  an outbound payment the scheme declined or returned. The debtor leg is a
  sub-ledger account, so route through `add-control-legs`."
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
       tx (domain/outbound-reversal->transaction
           payment
           debtor-account
           (:ledger-account-id pending))
       expanded-legs (ledger-accounts/add-control-legs
                      txn
                      bank-id
                      currency
                      (:legs tx))
       recorded (transactions/record-transaction
                 txn
                 (assoc tx :legs expanded-legs))
       {:keys [transaction-type legs]} recorded
       _ (balances/apply-legs txn bank-id legs transaction-type)]
      recorded)))

(defn reject-outbound
  "Process an outbound `transaction-rejected` event. Reverses the in-flight
  payment (DEBIT 1200 / CREDIT debtor) and flips the OutboundPayment to
  failed with the scheme's cancellation code/reason. Pending and held
  payments are reversible; an already-failed payment is an idempotent
  no-op; a completed (settled) payment cannot be reversed here."
  [config data]
  (let [{payment-id :end-to-end-id} data
        {:keys [cancellation-code cancellation-reason]} data]
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

          (= :outbound-payment-status-completed (:payment-status payment))
          (error/fail
           :payment/reject-outbound
           {:message "Cannot reverse an already-settled outbound payment"
            :payment-id payment-id})

          :else
          (let-nom>
            [failed (domain/failed-outbound-payment payment
                                                    cancellation-code
                                                    cancellation-reason)
             _ (store/save-outbound-payment txn failed)
             _ (record-reversal-leg txn payment)]
            (log/infof "Outbound payment rejected and reversed: %s"
                       {:payment-id payment-id
                        :cancellation-code cancellation-code})
            failed))))
     :payment/reject-outbound
     "Failed to reject outbound payment")))
