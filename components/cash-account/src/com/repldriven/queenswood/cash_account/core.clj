(ns com.repldriven.queenswood.cash-account.core
  (:require
    [com.repldriven.queenswood.cash-account.closure :as closure]
    [com.repldriven.queenswood.cash-account.domain :as domain]
    [com.repldriven.queenswood.cash-account.store :as store]

    [com.repldriven.queenswood.audit.interface :as audit]
    [com.repldriven.queenswood.balance-query.interface :as balances-q]
    [com.repldriven.queenswood.bank-activity.interface :as bank-activity]
    [com.repldriven.queenswood.balance.interface :as balances]
    [com.repldriven.queenswood.cash-account-product-query.interface :as
     products]
    [com.repldriven.queenswood.cash-account-query.interface :as q]
    [com.repldriven.queenswood.party-query.interface :as parties]
    [com.repldriven.queenswood.policy.interface :as policy]

    [com.repldriven.mono.error.interface :refer [let-nom>]]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]))

(defn- get-policies
  ([txn bank-id opts]
   (or (:policies opts)
       (policy/get-effective-policies-cached txn
                                             {:bank-id bank-id}
                                             (:policy-cache opts))))
  ([txn bank-id account-id opts]
   (or (:policies opts)
       (policy/get-effective-policies txn
                                      {:bank-id bank-id
                                       :account-id account-id}))))

(defn- get-aggregates
  [txn bank-id product-type account-type currency]
  (let-nom> [{:keys [total subtotal]}
             (q/get-aggregates txn bank-id product-type account-type currency)]
    {:cash-account
     {#{:bank-id} total
      #{:bank-id :product-type :account-type :currency} subtotal}}))

(defn- get-operation-aggregates
  [txn bank-id account-id today]
  (let-nom> [n (q/count-operations txn bank-id account-id today)]
    {:cash-account-operation {#{:bank-id :account-id :requested-on} n}}))

(defn- product-type-of
  [product-version]
  (:product-type product-version))

(defn- record-opening
  [txn account party product-version]
  (let [{:keys [bank-id account-id currency]} account]
    (let-nom> [schemes (domain/address-schemes product-version)]
      (bank-activity/record txn
                            {:bank-id bank-id
                             :event-name "cash-account-open-requested"
                             :data {:account-id account-id
                                    :holder-name (:legal-name party)
                                    :currency currency
                                    :address-schemes schemes}
                             :causation-id account-id
                             :dedup-key account-id}))))

(defn- request-at-provider
  "Record the activity entry that asks `account`'s provider to carry out
  `operation`."
  [txn account operation]
  (let [{:keys [bank-id account-id provider-account-id]} account
        {:keys [operation-id kind]} operation]
    (bank-activity/record txn
                          {:bank-id bank-id
                           :event-name "cash-account-operation-requested"
                           :data {:account-id account-id
                                  :operation-id operation-id
                                  :kind kind
                                  :provider-account-id provider-account-id}
                           :causation-id account-id
                           :dedup-key operation-id})))

(defn- record-operation
  "Save `operation` and, while it is pending and not a close, which the
  provider is asked to carry out once its balance has moved, the
  activity entry that asks the account's provider to carry it out."
  [txn account operation]
  (let-nom>
    [_ (store/save-operation txn operation nil)]
    (when (and (domain/pending-operation? operation)
               (not= :cash-account-operation-kind-close (:kind operation)))
      (request-at-provider txn account operation))))

(defn- or-already-opened
  "On a uniqueness violation — a redelivered or retried
  open-cash-account command carrying an already-seen idempotency-key —
  read the existing account back and return it, so the caller gets the
  original resource instead of a duplicate account or a bare rejection.
  Any other value passes through unchanged."
  [txn data result]
  (if (and (store/uniqueness-violation? result)
           (:idempotency-key data))
    (let-nom> [existing (q/find-account-by-idempotency-key
                         txn
                         (:bank-id data)
                         (:idempotency-key data))]
      (or existing result))
    result))

(defn- audit-entry
  "The audit entry of an act the command in `data` asked for, taking
  the account from `before` to `after`."
  [kind before after data]
  {:bank-id (:bank-id after)
   :kind kind
   :subject-kind :audit-subject-kind-cash-account
   :subject-id (:account-id after)
   :request-id (:request-id data)
   :before before
   :after after
   :performed-by (:actor data)})

(defn open-account
  ([txn data]
   (open-account txn data {}))
  ([txn data opts]
   (let [opts (utility/assoc-some opts :policy-cache (:policy-cache txn))]
     (or-already-opened
      txn
      data
      (store/transact
       txn
       (fn [txn]
         (let [{:keys [bank-id party-id product-id currency]} data
               today (utility/today)]
           (let-nom>
             [policies (telemetry/with-span
                        ["cash-account-policies"]
                        (get-policies txn
                                      bank-id
                                      opts))
              party (telemetry/with-span
                     ["cash-account-party"]
                     (parties/get-party txn bank-id party-id))
              product (telemetry/with-span
                       ["cash-account-product"]
                       (products/get-product txn bank-id product-id))
              product-version (products/active-version product today)
              aggregates (when product-version
                           (telemetry/with-span
                            ["cash-account-aggregates"]
                            (get-aggregates txn
                                            bank-id
                                            (product-type-of product-version)
                                            (domain/party->account-type party)
                                            currency)))
              account (domain/open-account
                       data
                       product-version
                       today
                       party
                       aggregates
                       policies)
              _ (telemetry/with-span
                 ["cash-account-balances"]
                 (balances/new-balances
                  txn
                  bank-id
                  (domain/opening-balances account product-version)
                  {:policies (policy/platform-policies policies)}))
              _ (telemetry/with-span ["cash-account-save"]
                                     (store/save-account
                                      txn
                                      account
                                      {:account-id (:account-id account)
                                       :status-after (:status account)
                                       :change-kind
                                       :cash-account-change-kind-open}))
              _ (telemetry/with-span
                 ["cash-account-record-activity"]
                 (record-opening txn account party product-version))
              _ (when (:audited? opts)
                  (audit/record txn
                                (audit-entry
                                 :audit-kind-cash-account-opened
                                 nil
                                 account
                                 data)))]
             account)))
       :cash-account/open
       "Failed to open account")))))

(defn- operate
  "Carry out an operation of `kind` on the account the command in `data`
  names. `transition` takes the transaction, the account, its operations
  for the day and the policies, and returns the account the operation
  leaves, saved under `change-kind` where there is one; `terms`, where
  given, takes the transaction, the account and the policies, and
  returns what the operation records it is carried out by. A command
  whose idempotency key already asked for an operation of the account is
  a retry after a lost reply, and gets back the account as it is: no
  operation, no save, no changelog entry."
  [txn data opts {:keys [kind transition terms change-kind audit-kind]}]
  (let [{:keys [bank-id account-id idempotency-key]} data
        today (utility/today)]
    (let-nom>
      [policies (get-policies txn bank-id account-id opts)
       account (q/get-account txn bank-id account-id)
       asked (when idempotency-key
               (q/find-operation-by-idempotency-key txn
                                                    bank-id
                                                    account-id
                                                    idempotency-key))]
      (if asked
        account
        (let-nom>
          [aggregates (get-operation-aggregates txn bank-id account-id today)
           updated (transition txn account aggregates policies)
           operation-terms (when terms (terms txn account policies))
           _ (when change-kind
               (store/save-account txn
                                   updated
                                   {:account-id account-id
                                    :status-before (:status account)
                                    :status-after (:status updated)
                                    :change-kind change-kind}))
           _ (record-operation txn
                               account
                               (domain/new-operation account
                                                     kind
                                                     today
                                                     data
                                                     operation-terms))
           _ (audit/record txn (audit-entry audit-kind account updated data))]
          updated)))))

(defn close-account
  ([txn data]
   (close-account txn data {}))
  ([txn data opts]
   (store/transact
    txn
    (fn [txn]
      (operate txn
               data
               opts
               {:kind :cash-account-operation-kind-close
                :transition (fn [txn account _ policies]
                              (closure/close txn data account policies))
                :terms (fn [txn account policies]
                         (closure/terms txn data account policies))
                :change-kind :cash-account-change-kind-close
                :audit-kind :audit-kind-cash-account-close-requested}))
    :cash-account/close
    "Failed to close account")))

(defn suspend-account
  ([txn data]
   (suspend-account txn data {}))
  ([txn data opts]
   (store/transact
    txn
    (fn [txn]
      (operate txn
               data
               opts
               {:kind :cash-account-operation-kind-suspend
                :transition (fn [_ account _ policies]
                              (domain/suspend-account account
                                                      (:actor data)
                                                      policies))
                :change-kind :cash-account-change-kind-suspend
                :audit-kind :audit-kind-cash-account-suspended}))
    :cash-account/suspend
    "Failed to suspend account")))

(defn resume-account
  ([txn data]
   (resume-account txn data {}))
  ([txn data opts]
   (store/transact
    txn
    (fn [txn]
      (operate txn
               data
               opts
               {:kind :cash-account-operation-kind-resume
                :transition (fn [_ account aggregates policies]
                              (domain/resume-account account
                                                     (:actor data)
                                                     aggregates
                                                     policies))
                :change-kind :cash-account-change-kind-resume
                :audit-kind :audit-kind-cash-account-resumed}))
    :cash-account/resume
    "Failed to resume account")))

(defn rotate-address
  ([txn data]
   (rotate-address txn data {}))
  ([txn data opts]
   (store/transact
    txn
    (fn [txn]
      (operate txn
               data
               opts
               {:kind :cash-account-operation-kind-rotate-address
                :transition (fn [_ account aggregates policies]
                              (domain/request-rotation account
                                                       aggregates
                                                       policies))
                :audit-kind :audit-kind-cash-account-rotation-requested}))
    :cash-account/rotate-address
    "Failed to rotate account address")))

(defn- provider-transition
  [txn bank-id account-id guard transition change-kind]
  (store/transact
   txn
   (fn [txn]
     (let-nom>
       [account (q/find-account txn bank-id account-id)]
       (when (and account (guard account))
         (let [updated (transition account)]
           (let-nom>
             [_ (store/save-account txn
                                    updated
                                    {:account-id account-id
                                     :status-before (:status account)
                                     :status-after (:status updated)
                                     :change-kind change-kind})]
             updated)))))
   :cash-account/provider-transition
   "Failed to apply provider transition"))

(defn- status?
  [status]
  (fn [account] (= status (:status account))))

(defn- settle-operation
  "Settle the pending operation a provider's event answers with `settle`,
  and, where `transition` is given, apply it to the operation's account,
  saved under `change-kind`. Returns the account so changed, or else the
  settled operation; an operation already settled is a redelivery, left
  as it is, and returns nil."
  ([txn event settle]
   (settle-operation txn event settle nil nil))
  ([txn event settle transition change-kind]
   (let [{:keys [bank-id account-id operation-id]} event]
     (store/transact
      txn
      (fn [txn]
        (let-nom>
          [operation (store/find-operation txn bank-id account-id operation-id)]
          (when (and operation (domain/pending-operation? operation))
            (let-nom>
              [settled (settle operation)
               _ (store/save-operation txn settled (:status operation))
               account (when transition
                         (q/find-account txn bank-id account-id))]
              (if-not account
                settled
                (let-nom> [updated (transition account operation)
                           _ (store/save-account
                              txn
                              updated
                              {:account-id account-id
                               :status-before (:status account)
                               :status-after (:status updated)
                               :change-kind change-kind})]
                  updated))))))
      :cash-account-operation/settle
      "Failed to settle account operation"))))

(defn- refused
  [reason]
  (fn [operation] (domain/refused-operation operation reason)))

(defn provider-operation-completed
  [txn event]
  (settle-operation txn event domain/completed-operation))

(defn provider-operation-refused
  [txn event]
  (settle-operation txn event (refused (:reason event))))

(defn provider-opened
  [txn {:keys [bank-id account-id] :as event}]
  (provider-transition txn
                       bank-id
                       account-id
                       (status? :cash-account-status-opening)
                       (fn [account]
                         (domain/provider-opened-account account event))
                       :cash-account-change-kind-open))

(defn provider-refused
  [txn {:keys [bank-id account-id reason]}]
  (provider-transition txn
                       bank-id
                       account-id
                       (status? :cash-account-status-opening)
                       (fn [account] (domain/refused-account account reason))
                       :cash-account-change-kind-open))

(defn- emptied?
  "Whether `account` holds no balance, so it may be closed."
  [txn account]
  (let-nom> [account-balances (balances-q/list-balances txn
                                                        (:bank-id account)
                                                        (:account-id account))]
    (domain/emptied? account-balances)))

(defn provider-closed
  "Closes the account the provider closed, or, where it still holds a
  balance, leaves it closing and refuses the close operation, saying
  so."
  [txn event]
  (let [{:keys [bank-id account-id]} event]
    (store/transact
     txn
     (fn [txn]
       (let-nom> [account (q/find-account txn bank-id account-id)
                  empty (or (nil? account) (emptied? txn account))]
         (if empty
           (settle-operation txn
                             event
                             domain/completed-operation
                             (fn [account _] (domain/closed-account account))
                             :cash-account-change-kind-close)
           (settle-operation txn
                             event
                             (refused domain/held-on-close)))))
     :cash-account/provider-closed
     "Failed to close account")))

(defn provider-close-refused
  [txn event]
  (settle-operation txn
                    event
                    (refused (:reason event))
                    domain/close-refused-account
                    :cash-account-change-kind-close-refused))

(defn closure-paid
  "Once a close's balance has moved, asks the account's provider to close
  the account behind it, or, where the provider never held one, closes
  the account itself. A close no longer pending is a redelivery, left as
  it is."
  [txn event]
  (let [{:keys [bank-id account-id operation-id]} event]
    (store/transact
     txn
     (fn [txn]
       (let-nom>
         [operation (store/find-operation txn bank-id account-id operation-id)
          account (when (and operation (domain/pending-operation? operation))
                    (q/find-account txn bank-id account-id))]
         (cond (nil? account)
               nil

               (:provider-account-id account)
               (request-at-provider txn account operation)

               :else
               (provider-closed txn event))))
     :cash-account/closure-paid
     "Failed to close account")))

(defn closure-refused
  "Refuses the close a closure refused, with its reason, and returns the
  account to the status it was closed from."
  [txn event]
  (provider-close-refused txn
                          (assoc event :reason (:refused-reason event))))

(defn provider-reissued
  [txn event]
  (settle-operation txn
                    event
                    domain/completed-operation
                    (fn [account _] (domain/reissued-account account event))
                    :cash-account-change-kind-rotate-address))

(defn provider-reissue-failed
  [txn event]
  (settle-operation txn event (refused (:reason event))))

(defn migrate-account
  "Repin an account the caller already holds, writing into the caller's
  transaction. Reads nothing: the account, the target version and the
  policies all arrive resolved.

  This is the shape a batch needs. A pass over a million accounts has
  already streamed each one, has one target version for the whole
  cohort, and resolves policy once for the run — so re-reading any of
  that per account would be a million round-trips for three values that
  never change. `migrate-product` is the single-account door onto the
  same work.

  A migration leaves the status alone, so the changelog metadata carries
  the same status on both sides. It is still written: the account record
  changed, and a reader tailing the changelog needs to see that as much
  as it needs to see a status flip."
  [txn account target-version policies actor]
  (let-nom>
    [updated (domain/migrate-product account
                                     target-version
                                     (utility/today)
                                     policies
                                     actor)
     _ (store/save-account txn
                           updated
                           {:account-id (:account-id account)
                            :status-before (:status account)
                            :status-after (:status updated)
                            :change-kind
                            :cash-account-change-kind-migrate})]
    updated))

(defn migrate-product
  "Repin one account by id, resolving the target and the policies it is
  checked against inside the transaction. The command path; a batch uses
  `migrate-account` instead and resolves those once for the run."
  ([txn data]
   (migrate-product txn data {}))
  ([txn data opts]
   (store/transact
    txn
    (fn [txn]
      (let [{:keys [bank-id account-id target-product-id target-version-id]}
            data]
        (let-nom>
          [policies (get-policies txn bank-id account-id opts)
           account (q/get-account txn bank-id account-id)
           target-version (products/get-version txn
                                                bank-id
                                                target-product-id
                                                target-version-id)]
          (migrate-account txn
                           account
                           target-version
                           policies
                           (:actor opts)))))
    :cash-account/migrate-product
    "Failed to migrate account product")))
