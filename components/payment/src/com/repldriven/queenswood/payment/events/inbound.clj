(ns com.repldriven.queenswood.payment.events.inbound
  (:require
    [com.repldriven.queenswood.payment.domain.checks :as checks]
    [com.repldriven.queenswood.payment.domain.inbound :as inbound]
    [com.repldriven.queenswood.payment.store :as store]

    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.bank-activity.interface :as bank-activity]
    [com.repldriven.queenswood.cash-account-query.interface :as cash-accounts]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.payment-query.interface :as q]
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.transaction.interface :as transactions]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- check-debit-credit-code
  [debit-credit-code]
  (when (not= :debit-credit-code-credit debit-credit-code)
    (error/fail
     :payment/settle-inbound
     {:message "Inbound payment settlement for non-credit is not permissible"
      :debit-credit-code debit-credit-code})))

(defn- post-to-suspense
  [txn data bank-id receiving-account-id]
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
       transaction (inbound/inbound-suspense->transaction
                    data
                    bank-id
                    (:ledger-account-id cash)
                    (:ledger-account-id suspense)
                    receiving-account-id)
       recorded (transactions/record-transaction txn transaction)
       {:keys [transaction-type legs]} recorded
       stored (ledger-accounts/stored-legs txn bank-id currency legs)
       _ (balances/apply-legs txn bank-id stored transaction-type)]
      recorded)))

(defn- record-suspended
  [txn payment creditor-account-id]
  (let [{:keys [bank-id payment-id]} payment]
    (bank-activity/record txn
                          {:bank-id bank-id
                           :event-name "inbound-payment-suspended"
                           :data (utility/assoc-some
                                  (inbound/return-payment payment)
                                  :creditor-account-id
                                  creditor-account-id)
                           :causation-id payment-id
                           :dedup-key payment-id})))

(defn- park-in-suspense
  "Park an inbound the receiving account could not take in its bank's
  2500 suspense and persist a `suspended` InboundPayment, with the reason
  it was refused, for later reconciliation."
  [txn data account business-day refusal]
  (let-nom>
    [recorded (post-to-suspense txn
                                data
                                (:bank-id account)
                                (:account-id account))
     {:keys [transaction-id]} recorded
     payment (inbound/suspended-inbound-payment data
                                                (:bank-id account)
                                                business-day
                                                transaction-id
                                                refusal)
     _ (store/save-inbound-payment
        txn
        payment
        {:change-kind :inbound-payment-change-kind-suspend})
     _ (record-suspended txn payment (:account-id account))]
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
            transaction (inbound/inbound-payment->transaction
                         data
                         account
                         (:ledger-account-id cash)
                         policies
                         aggregates)]
        (if (checks/refused? transaction)
          (do (log/infof "Inbound settlement refused, parked in suspense: %s"
                         {:kind (error/kind transaction)
                          :account-id account-id})
              (park-in-suspense txn
                                data
                                account
                                business-day
                                (inbound/acceptance-refusal transaction)))
          (let-nom>
            [_ transaction
             checked-legs (ledger-accounts/ensure-controls
                           txn
                           bank-id
                           currency
                           (:legs transaction))
             transaction+legs (transactions/record-transaction
                               txn
                               (assoc transaction :legs checked-legs))
             {:keys [transaction-id transaction-type legs]} transaction+legs
             stored (ledger-accounts/stored-legs txn bank-id currency legs)
             _ (balances/apply-legs txn bank-id stored transaction-type)
             payment (inbound/new-inbound-payment data
                                                  account-id
                                                  bank-id
                                                  business-day
                                                  transaction-id)
             _ (store/save-inbound-payment
                txn
                payment
                {:change-kind :inbound-payment-change-kind-settle})]
            payment))))))

(defn- suspend-held
  [txn data held refusal]
  (let [{:keys [bank-id creditor-account-id]} held
        {:keys [scheme-transaction-id]} data]
    (let-nom>
      [recorded (post-to-suspense txn data bank-id creditor-account-id)
       {:keys [transaction-id]} recorded
       suspended (inbound/suspended-from-held held
                                              scheme-transaction-id
                                              transaction-id
                                              refusal)
       _ (store/save-inbound-payment
          txn
          suspended
          {:change-kind :inbound-payment-change-kind-suspend
           :status-before (:payment-status held)})
       _ (record-suspended txn suspended creditor-account-id)]
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
                         (inbound/release-count today-count held business-day)}}
            transaction (inbound/inbound-release->transaction
                         held
                         account
                         (:ledger-account-id cash)
                         policies
                         aggregates)]
        (if (checks/refused? transaction)
          (do (log/infof "Inbound release refused, parked in suspense: %s"
                         {:kind (error/kind transaction)
                          :account-id account-id})
              (suspend-held txn
                            data
                            held
                            (inbound/acceptance-refusal transaction)))
          (let-nom>
            [_ transaction
             checked-legs (ledger-accounts/ensure-controls
                           txn
                           bank-id
                           currency
                           (:legs transaction))
             recorded (transactions/record-transaction
                       txn
                       (assoc transaction :legs checked-legs))
             {:keys [transaction-id transaction-type legs]} recorded
             stored (ledger-accounts/stored-legs txn bank-id currency legs)
             _ (balances/apply-legs txn bank-id stored transaction-type)
             released (inbound/settled-from-held held
                                                 scheme-transaction-id
                                                 transaction-id)
             _ (store/save-inbound-payment
                txn
                released
                {:change-kind :inbound-payment-change-kind-release
                 :status-before (:payment-status held)})]
            released))))))

(defn- record-admitted-settlement
  "Settle an admitted inbound: post DEBIT 1100 / CREDIT creditor without
  checking again, and flip the admitted record to `settled`."
  [txn data account admitted]
  (let [{:keys [bank-id]} account
        {:keys [scheme-transaction-id]} data
        {:keys [currency]} admitted]
    (let-nom>
      [cash (ledger-accounts/find-by-code
             txn
             bank-id
             :gl-account-code-cash-at-correspondent
             currency)
       transaction (inbound/admitted-inbound->transaction
                    admitted
                    account
                    (:ledger-account-id cash))
       checked-legs (ledger-accounts/ensure-controls
                     txn
                     bank-id
                     currency
                     (:legs transaction))
       recorded (transactions/record-transaction
                 txn
                 (assoc transaction :legs checked-legs))
       {:keys [transaction-id transaction-type legs]} recorded
       stored (ledger-accounts/stored-legs txn bank-id currency legs)
       _ (balances/apply-legs txn bank-id stored transaction-type)
       settled (inbound/settled-from-held admitted
                                          scheme-transaction-id
                                          transaction-id)
       _ (store/save-inbound-payment
          txn
          settled
          {:change-kind :inbound-payment-change-kind-settle
           :status-before (:payment-status admitted)})]
      settled)))

(defn- admit
  [txn data account business-day]
  (let [{:keys [account-id bank-id]} account]
    (let-nom>
      [policies (policy/get-effective-policies txn {:bank-id bank-id})
       today-count (q/count-inbound-by-org-business-day txn
                                                        bank-id
                                                        business-day)]
      (let [accepted (inbound/check-inbound-acceptance
                      data
                      account
                      policies
                      {:inbound-payment {#{:bank-id :business-day}
                                         today-count}})]
        (cond
         (checks/refused? accepted)
         (let [refusal (inbound/acceptance-refusal accepted)]
           (log/infof "Inbound payment rejected at admission: %s"
                      (assoc refusal :end-to-end-id (:end-to-end-id data)))
           (merge {:admitted false} refusal))

         (error/anomaly? accepted)
         accepted

         :else
         (let [admitted (inbound/admitted-inbound-payment data
                                                          account-id
                                                          bank-id
                                                          business-day)]
           (let-nom> [_ (store/save-inbound-payment
                         txn
                         admitted
                         {:change-kind :inbound-payment-change-kind-admit})]
             (log/infof "Inbound payment admitted: %s"
                        {:payment-id (:payment-id admitted)
                         :account-id account-id})
             {:admitted true :payment-id (:payment-id admitted)})))))))

(defn admit-inbound
  [config data]
  (let [{:keys [creditor-bban end-to-end-id amount]} data
        business-day (checks/current-business-day
                      (utility/now)
                      (:business-day-cutoff config))]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [account (cash-accounts/get-account-by-bban txn creditor-bban)
          admitted (when account
                     (q/find-matching-inbound txn
                                              end-to-end-id
                                              (:account-id account)
                                              amount))
          refusal (when-not admitted (inbound/account-refusal account))]
         (cond
          admitted
          {:admitted true :payment-id (:payment-id admitted)}

          refusal
          (do (log/infof "Inbound payment rejected at admission: %s"
                         (assoc refusal :end-to-end-id end-to-end-id))
              (merge {:admitted false} refusal))

          :else
          (admit txn data account business-day))))
     :payment/admit-inbound
     "Failed to admit inbound payment")))

(defn- settle
  [config data]
  (let [{:keys [debit-credit-code creditor-bban
                scheme-transaction-id end-to-end-id amount]}
        data
        business-day (checks/current-business-day
                      (utility/now)
                      (:business-day-cutoff config))]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [_ (check-debit-credit-code debit-credit-code)
          account (cash-accounts/get-account-by-bban txn creditor-bban)
          settled (q/get-inbound-payment txn scheme-transaction-id)
          admitted (when account
                     (q/find-open-admission txn
                                            end-to-end-id
                                            (:account-id account)
                                            amount))
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

          (and admitted (not (checks/operable? account)))
          (do (log/infof "Admitted inbound to a non-operable account: %s"
                         {:account-id (:account-id account)
                          :account-status (:account-status account)})
              (suspend-held txn
                            data
                            admitted
                            (inbound/account-refusal account)))

          ;; The BBAN resolves, but the account cannot take a credit —
          ;; park the funds in suspense as an unmatched BBAN is, so the
          ;; receipt stays recoverable.
          (and account (not (checks/operable? account)))
          (do (log/infof "Inbound settlement to a non-operable account: %s"
                         {:account-id (:account-id account)
                          :account-status (:account-status account)})
              (park-in-suspense txn
                                data
                                account
                                business-day
                                (inbound/account-refusal account)))

          admitted
          (record-admitted-settlement txn data account admitted)

          ;; Release of a previously-held inbound — settle it to the
          ;; account and flip the held record to settled.
          held
          (record-inbound-release txn data account held business-day)

          (nil? account)
          (error/fail :payment/unknown-creditor
                      {:message "No account holds the inbound's address"
                       :bban (:creditor-bban data)})

          :else
          (record-inbound-settlement txn data account business-day))))
     :payment/settle-inbound
     "Failed to settle inbound payment")))

(defn settle-inbound
  "Settle an inbound credit against the creditor resolved by BBAN. A
  creditor that is not opened — suspended, closing, closed, or still
  opening — is parked in 2500 suspense rather than credited, as one a
  check refuses is; a held record for it, if any, stays `held`. A parked
  inbound is recorded as the bank's activity, which sends it back where
  the provider declares `returns: [inbound]`."
  [config data]
  (settle config data))

(defn hold-inbound
  "An inbound ClearBank is holding for screening. Record it `held` (creditor
  resolved by BBAN); no money moves — the funds are held at ClearBank, not
  ours yet. Idempotent on any inbound recorded for the same end-to-end
  id, creditor and amount, whatever its status, so a hold redelivered
  after its settlement records nothing; a held to an unmatched BBAN, or to
  a creditor that is not opened, is logged and ignored — the settle that
  follows finds no held record and parks in suspense."
  [config data]
  (let [{:keys [creditor-bban end-to-end-id amount]} data
        business-day (checks/current-business-day
                      (utility/now)
                      (:business-day-cutoff config))]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [account (cash-accounts/get-account-by-bban txn creditor-bban)
          existing (when account
                     (q/find-matching-inbound txn
                                              end-to-end-id
                                              (:account-id account)
                                              amount))]
         (cond
          existing
          (do (log/infof "Inbound hold already recorded: %s"
                         {:end-to-end-id end-to-end-id
                          :payment-status (:payment-status existing)})
              existing)

          (and account (not (checks/operable? account)))
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
                payment (inbound/held-inbound-payment data
                                                      account-id
                                                      bank-id
                                                      business-day)]
            (let-nom> [_ (store/save-inbound-payment
                          txn
                          payment
                          {:change-kind :inbound-payment-change-kind-hold})]
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
        (inbound/select-hold-to-return holds end-to-end-id)))))

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
           (let-nom> [returned (inbound/returned-inbound-payment held)
                      _ (store/save-inbound-payment
                         txn
                         returned
                         {:change-kind :inbound-payment-change-kind-return
                          :status-before (:payment-status held)})]
             (log/infof "Inbound held transaction returned: %s"
                        {:end-to-end-id end-to-end-id})
             returned))))
     :payment/return-inbound
     "Failed to return inbound payment")))

(defn return-suspended
  "A suspended inbound the provider sent back to its sender: post DEBIT
  2500 suspense / CREDIT 1100 and move it to `returned`. A second report
  is a no-op; one for no payment, or for one not suspended, fails."
  [config data]
  (let [{:keys [scheme-transaction-id]} data]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [payment (q/get-inbound-payment txn scheme-transaction-id)]
         (cond
          (nil? payment)
          (error/fail :payment/return-inbound
                      {:message "No inbound payment carries the returned id"
                       :scheme-transaction-id scheme-transaction-id})

          (= :inbound-payment-status-returned (:payment-status payment))
          (do (log/infof "Inbound payment return already processed: %s"
                         {:payment-id (:payment-id payment)})
              payment)

          (not= :inbound-payment-status-suspended (:payment-status payment))
          (error/fail :payment/return-inbound
                      {:message "Cannot return an inbound payment not suspended"
                       :payment-id (:payment-id payment)
                       :payment-status (:payment-status payment)})

          :else
          (let [{:keys [bank-id currency]} payment]
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
               recorded (transactions/record-transaction
                         txn
                         (inbound/inbound-return->transaction
                          payment
                          (:ledger-account-id cash)
                          (:ledger-account-id suspense)))
               {:keys [transaction-type legs]} recorded
               stored (ledger-accounts/stored-legs txn bank-id currency legs)
               _ (balances/apply-legs txn bank-id stored transaction-type)
               returned (inbound/returned-inbound-payment payment)
               _ (store/save-inbound-payment
                  txn
                  returned
                  {:change-kind :inbound-payment-change-kind-return
                   :status-before (:payment-status payment)})]
              (log/infof "Suspended inbound payment returned: %s"
                         {:payment-id (:payment-id payment)})
              returned)))))
     :payment/return-inbound
     "Failed to return inbound payment")))

(defn return-failed
  "A suspended inbound the provider did not send back: it stays in
  suspense, recording the provider's reason. A second report, or one for
  an inbound no longer suspended, is a no-op; one for no payment fails."
  [config data]
  (let [{:keys [scheme-transaction-id reason]} data]
    (store/transact
     config
     (fn [txn]
       (let-nom>
         [payment (q/get-inbound-payment txn scheme-transaction-id)]
         (cond
          (nil? payment)
          (error/fail :payment/return-inbound
                      {:message
                       "No inbound payment carries the failed return's id"
                       :scheme-transaction-id scheme-transaction-id})

          (or (not= :inbound-payment-status-suspended (:payment-status payment))
              (:return-failure-reason payment))
          (do (log/infof "Inbound return failure already processed: %s"
                         {:payment-id (:payment-id payment)
                          :payment-status (:payment-status payment)})
              payment)

          :else
          (let [failed (inbound/return-failed-inbound-payment
                        payment
                        (or reason "The provider did not return the payment"))]
            (let-nom>
              [_ (store/save-inbound-payment
                  txn
                  failed
                  {:change-kind :inbound-payment-change-kind-return-failed
                   :status-before (:payment-status payment)})]
              (log/warnf "Suspended inbound payment not returned: %s"
                         {:payment-id (:payment-id payment) :reason reason})
              failed)))))
     :payment/return-inbound
     "Failed to record an inbound payment's failed return")))
