(ns com.repldriven.queenswood.schema.interface
  "Bank-specific protobuf schema bridge. Wraps the generated
  `com.repldriven.queenswood.schema.*` namespaces with EDN-friendly
  converters: `pb->X` parses bytes into a Clojure map, `X->pb`
  serialises a map to bytes, and `X->java` parses bytes into the
  generated Java class. Also exposes enum-label converters used by
  FDB index queries."
  (:require
    [com.repldriven.queenswood.schema.account_product_type :as
     account-product-type]
    [com.repldriven.queenswood.schema.actor :as actor]
    [com.repldriven.queenswood.schema.balance :as balance]
    [com.repldriven.queenswood.schema.bank :as bank]
    [com.repldriven.queenswood.schema.cash_account :as cash-account]
    [com.repldriven.queenswood.schema.cash_account_migration :as
     cash-account-migration]
    [com.repldriven.queenswood.schema.cash_account_product :as
     cash-account-product]
    [com.repldriven.queenswood.schema.changelog :as changelog]
    [com.repldriven.queenswood.schema.circuit_breaker :as circuit-breaker]
    [com.repldriven.queenswood.schema.clearbank :as clearbank]
    [com.repldriven.queenswood.schema.company :as company]
    [com.repldriven.queenswood.schema.email :as email]
    [com.repldriven.queenswood.schema.form3 :as form3]
    [com.repldriven.queenswood.schema.idempotency :as idempotency]
    [com.repldriven.queenswood.schema.idv :as idv]
    [com.repldriven.queenswood.schema.interest :as interest]
    [com.repldriven.queenswood.schema.ledger_account :as ledger-account]
    [com.repldriven.queenswood.schema.member :as member]
    [com.repldriven.queenswood.schema.modulr :as modulr]
    [com.repldriven.queenswood.schema.onfido :as onfido]
    [com.repldriven.queenswood.schema.outbound_intent :as outbound-intent]
    [com.repldriven.queenswood.schema.party :as party]
    [com.repldriven.queenswood.schema.payee_check :as payee-check]
    [com.repldriven.queenswood.schema.payment :as payment]
    [com.repldriven.queenswood.schema.policy :as policy]
    [com.repldriven.queenswood.schema.reward :as reward]
    [com.repldriven.queenswood.schema.scheduler :as scheduler]
    [com.repldriven.queenswood.schema.transaction :as transaction]
    [com.repldriven.queenswood.schema.user :as user]
    [com.repldriven.queenswood.schema.webhook :as webhook]
    [com.repldriven.queenswood.schema.zyphe :as zyphe]

    [protojure.protobuf :as proto])
  (:import
    (com.repldriven.queenswood.schema.balance
     AccountBalanceProto$AccountBalance)
    (com.repldriven.queenswood.schema.cash_account_migration
     CashAccountMigrationProto$CashAccountMigration
     CashAccountMigrationRunProto$CashAccountMigrationRun
     CashAccountMigrationAccountRunProto$CashAccountMigrationAccountRun)
    (com.repldriven.queenswood.schema.cash_account_product
     CashAccountProductProto$CashAccountProduct
     CashAccountProductTemplateProto$CashAccountProductTemplate
     CashAccountProductTypesProto$IsoCashAccountType)
    (com.repldriven.queenswood.schema.actor ActorProto$ActorKind)
    (com.repldriven.queenswood.schema.cash_account
     CashAccountProto$CashAccount)
    (com.repldriven.queenswood.schema.company CompanyProto$Company)
    (com.repldriven.queenswood.schema.email
     EmailDeliveryProto$EmailDelivery)
    (com.repldriven.queenswood.schema.idempotency IdempotencyProto$Idempotency)
    (com.repldriven.queenswood.schema.idv IdvProto$Idv
                                          IdvSessionProto$IdvSession)
    (com.repldriven.queenswood.schema.interest
     InterestRunProto$InterestRun
     InterestAccountRunProto$InterestAccountRun)
    (com.repldriven.queenswood.schema.ledger_account
     LedgerAccountProto$LedgerAccount
     LedgerAccountProto$LedgerAccountCode)
    (com.repldriven.queenswood.schema.scheduler
     SchedulerJobProto$SchedulerJob
     SchedulerRunProto$SchedulerRun)
    (com.repldriven.queenswood.schema.bank BankProto$Bank)
    (com.repldriven.queenswood.schema.party PartyProto$Party)
    (com.repldriven.queenswood.schema.payment
     InboundPaymentProto$InboundPayment
     InternalPaymentProto$InternalPayment
     OutboundPaymentProto$OutboundPayment
     PaymentProviderTransferProto$PaymentProviderTransfer)
    (com.repldriven.queenswood.schema.payee_check
     PayeeCheckProto$PayeeCheck)
    (com.repldriven.queenswood.schema.clearbank
     ClearbankOutboxEventProto$ClearbankOutboxEvent
     ClearbankOutboundIntentProto$ClearbankOutboundIntent)
    (com.repldriven.queenswood.schema.onfido
     OnfidoOutboxEventProto$OnfidoOutboxEvent
     OnfidoOutboundIntentProto$OnfidoOutboundIntent)
    (com.repldriven.queenswood.schema.policy
     PolicyProto$Policy
     PolicyBindingProto$PolicyBinding)
    (com.repldriven.queenswood.schema.reward
     AccountRewardProto$AccountReward)
    (com.repldriven.queenswood.schema.transaction
     TransactionLegProto$TransactionLeg
     TransactionProto$Transaction
     TransactionProto$TransactionType)
    (com.repldriven.queenswood.schema.user
     UserProto$User
     UserProto$IdentityProvider)
    (com.repldriven.queenswood.schema.member
     InvitationProto$Invitation
     InvitationProto$InvitationStatus
     MemberProto$Member
     MemberProto$MemberStatus
     MemberProto$Role
     MemberRoleChangeProto$MemberRoleChange)
    (com.repldriven.queenswood.schema.webhook
     WebhookDeliveryProto$WebhookDelivery
     WebhookDeliveryAttemptProto$WebhookDeliveryAttempt
     WebhookEndpointProto$WebhookEndpoint
     WebhookNotificationProto$WebhookNotification)
    (com.repldriven.queenswood.schema.circuit_breaker
     CircuitBreakerProto$CircuitBreaker)
    (com.repldriven.queenswood.schema.outbound_intent
     OutboundIntentStatusProto$OutboundIntentStatus)
    (com.repldriven.queenswood.schema.zyphe
     ZypheOutboxEventProto$ZypheOutboxEvent
     ZypheOutboundIntentProto$ZypheOutboundIntent)
    (com.repldriven.queenswood.schema.form3
     Form3OutboundIntentProto$Form3OutboundIntent
     Form3OutboxEventProto$Form3OutboxEvent)
    (com.repldriven.queenswood.schema.modulr
     ModulrOutboxEventProto$ModulrOutboxEvent
     ModulrOutboundIntentProto$ModulrOutboundIntent)))

(def ^{:doc "Parse AccountBalance protobuf bytes into a Clojure map."}
     pb->AccountBalance
  balance/pb->AccountBalance)

(defn AccountBalance->pb
  "Serialise an AccountBalance map to protobuf bytes.

  Args:
  - m: AccountBalance map matching the generated schema."
  [m]
  (proto/->pb (balance/new-AccountBalance m)))

(defn AccountBalance->java
  "Parse an AccountBalance map into the generated Java protobuf class.

  Args:
  - m: AccountBalance map matching the generated schema."
  [m]
  (AccountBalanceProto$AccountBalance/parseFrom (AccountBalance->pb m)))

(def ^{:doc "Map of Balance type label to protobuf int value."}
     balance-type->int
  balance/BalanceType-label2val)

(def ^{:doc "Map of Balance status label to protobuf int value."}
     balance-status->int
  balance/BalanceStatus-label2val)

(def ^{:doc "Map of AccountProductType label to protobuf int value."}
     product-type->int
  account-product-type/AccountProductType-label2val)

(def ^{:doc "Map of AccountProductType protobuf int value to label."}
     int->product-type
  account-product-type/AccountProductType-val2label)

(def ^{:doc "Map of CashAccount AccountType label to protobuf int
  value."}
     account-type->int
  cash-account/AccountType-label2val)

(def ^{:doc "Map of IsoCashAccountType label to protobuf int value."}
     iso-cash-account-type->int
  cash-account-product/IsoCashAccountType-label2val)

(def ^{:doc "Map of CompanyRegistry label to protobuf int value."}
     company-registry->int
  company/CompanyRegistry-label2val)

(def
  ^{:doc
    "Map of LedgerAccountCode role label to protobuf int value — the
  chart number itself, e.g. :ledger-account-code-suspense -> 2500."}
  ledger-account-code->int
  ledger-account/LedgerAccountCode-label2val)

(def ^{:doc "Map of LedgerAccountCode protobuf int value to role label."}
     int->ledger-account-code
  ledger-account/LedgerAccountCode-val2label)

(defn ledger-account-code->pb-enum
  "Convert a code role keyword to the protobuf enum value, for
  use as the comparand in an FDB enum-field index query."
  [code]
  (LedgerAccountProto$LedgerAccountCode/forNumber
   (ledger-account-code->int code)))

(def ^{:doc "Map of OutboundIntentStatus label to protobuf int value."}
     outbound-intent-status->int
  outbound-intent/OutboundIntentStatus-label2val)

(defn outbound-intent-status->pb-enum
  "Convert an outbound-intent-status keyword to the protobuf enum value,
  for use as the comparand in an FDB enum-field index query.

  Args:
  - outbound-intent-status: `:outbound-intent-status-*` keyword."
  [outbound-intent-status]
  (OutboundIntentStatusProto$OutboundIntentStatus/forNumber
   (outbound-intent-status->int outbound-intent-status)))

(defn iso-cash-account-type->pb-enum
  "Convert an iso-cash-account-type keyword to the protobuf enum
  value, for use in FDB index queries.

  Args:
  - iso-cash-account-type: `:iso-cash-account-type-*` keyword."
  [iso-cash-account-type]
  (CashAccountProductTypesProto$IsoCashAccountType/forNumber
   (iso-cash-account-type->int iso-cash-account-type)))

(def transaction-type->int transaction/TransactionType-label2val)

(def ^{:doc "Map of LegSide label to protobuf int value."} leg-side->int
  transaction/LegSide-label2val)

(defn transaction-type->pb-enum
  "Convert a transaction-type keyword to the protobuf enum value, for
  use as the comparand in an FDB enum-field index query."
  [transaction-type]
  (TransactionProto$TransactionType/forNumber
   (transaction-type->int transaction-type)))

(defn- plain-embedded
  "`pb->` hands an embedded message back as a protojure record, which
  reitit cannot coerce, so the one under `k` becomes a plain map."
  [m k]
  (cond-> m
          (some? (get m k))
          (update k #(into {} %))))

(defn- without-unset
  [record unset]
  (reduce-kv (fn [m k v]
               (cond-> m
                       (= v (get m k))
                       (dissoc k)))
             (into {} record)
             unset))

(defn- plain-interest
  "A version's interest terms as plain maps, without a step's start, a
  band's `up-to` or a payment's day, day of month or month where none
  was given."
  [interest]
  (-> (into {} interest)
      (update
       :steps
       (fn [steps]
         (mapv (fn [step]
                 (-> (without-unset step {:starts-on 0 :starts-after-months 0})
                     (update :bands
                             (fn [bands]
                               (mapv (fn [band]
                                       (without-unset band {:up-to 0}))
                                     bands)))))
               steps)))
      (update :payment
              without-unset
              {:day :interest-payment-day-unknown :day-of-month 0 :month 0})))

(defn pb->CashAccountProduct
  "Parse CashAccountProduct protobuf bytes into a Clojure map, without the
  optional fields the version was never given: a zero `effective_to`,
  `published_at` or `discarded_at`, an empty idempotency key, no interest
  terms, or no publishing or discarding actor. An embedded message is a
  plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (let [version (cash-account-product/pb->CashAccountProduct input)]
    (cond->
     (reduce
      plain-embedded
      (update version :reward-terms (fn [terms] (mapv #(into {} %) terms)))
      [:created-by :published-by :discarded-by])

     (nil? (:interest-terms version))
     (dissoc :interest-terms)

     (zero? (:updated-at version 0))
     (dissoc :updated-at)

     (some? (:interest-terms version))
     (update :interest-terms plain-interest)

     (nil? (:published-by version))
     (dissoc :published-by)

     (nil? (:discarded-by version))
     (dissoc :discarded-by)

     (zero? (:effective-to version 0))
     (dissoc :effective-to)

     (zero? (:published-at version 0))
     (dissoc :published-at)

     (zero? (:discarded-at version 0))
     (dissoc :discarded-at)

     (= "" (:idempotency-key version))
     (dissoc :idempotency-key))))

(defn CashAccountProduct->pb
  "Serialise a CashAccountProduct map to protobuf bytes.

  Args:
  - m: CashAccountProduct map matching the generated schema."
  [m]
  (proto/->pb (cash-account-product/new-CashAccountProduct m)))

(defn CashAccountProduct->java
  "Parse a CashAccountProduct map into the generated Java protobuf
  class.

  Args:
  - m: CashAccountProduct map matching the generated schema."
  [m]
  (CashAccountProductProto$CashAccountProduct/parseFrom
   (CashAccountProduct->pb m)))

(defn pb->CashAccountProductTemplate
  "Parse CashAccountProductTemplate protobuf bytes into a map, an
  `updated-at` present only when set.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (cash-account-product/pb->CashAccountProductTemplate input)
                 {:updated-at 0}))

(defn CashAccountProductTemplate->pb
  "Serialise a CashAccountProductTemplate map to protobuf bytes.

  Args:
  - m: CashAccountProductTemplate map matching the generated schema."
  [m]
  (proto/->pb (cash-account-product/new-CashAccountProductTemplate m)))

(defn CashAccountProductTemplate->java
  "Parse a CashAccountProductTemplate map into the generated Java
  protobuf class.

  Args:
  - m: CashAccountProductTemplate map matching the generated schema."
  [m]
  (CashAccountProductTemplateProto$CashAccountProductTemplate/parseFrom
   (CashAccountProductTemplate->pb m)))

(def ^:private company-unset
  {:jurisdiction ""
   :incorporated-on 0
   :registered-office-address nil
   :updated-at 0})

(def ^:private address-unset
  {:address-line-1 "" :locality "" :postal-code "" :country ""})

(defn- plain-company
  "A company, or a bank's binding to one, as a plain map without the
  optional fields and address lines it was never given."
  [company]
  (let [company (without-unset company company-unset)]
    (cond-> company
            (:registered-office-address company)
            (update :registered-office-address without-unset address-unset))))

(defn pb->Company
  "Parse Company protobuf bytes into a Clojure map. Each optional field,
  and each line of the registered office address, is present only when
  set. The address is a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (plain-company (company/pb->Company input)))

(defn Company->pb
  "Serialise a Company map to protobuf bytes.

  Args:
  - m: Company map matching the generated schema."
  [m]
  (proto/->pb (company/new-Company m)))

(defn Company->java
  "Parse a Company map into the generated Java protobuf class.

  Args:
  - m: Company map matching the generated schema."
  [m]
  (CompanyProto$Company/parseFrom (Company->pb m)))

(defn pb->Idempotency
  "Parse Idempotency protobuf bytes into a Clojure map. A pending entry
  carries no `:response` or `:completed-at`; a completed one's response
  is a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (idempotency/pb->Idempotency input)
      (without-unset {:response nil :completed-at 0})
      (plain-embedded :response)))

(defn Idempotency->pb
  "Serialise an Idempotency map to protobuf bytes."
  [m]
  (proto/->pb (idempotency/new-Idempotency m)))

(defn Idempotency->java
  "Parse an Idempotency map into the generated Java protobuf class."
  [m]
  (IdempotencyProto$Idempotency/parseFrom (Idempotency->pb m)))

(def ^:private bank-unset
  {:updated-at 0
   :status-changed-at 0
   :status-changed-by nil
   :tier-changed-at 0
   :tier-changed-by nil})

(defn pb->Bank
  "Parse Bank protobuf bytes into a Clojure map, a status or tier
  change's `_at` and `_by` present only when set, its actors plain maps,
  and its company binding, where it has one, as a company is."
  [input]
  (let [bank (reduce plain-embedded
                     (without-unset (bank/pb->Bank input)
                                    (assoc bank-unset :company-binding nil))
                     [:created-by :status-changed-by :tier-changed-by])]
    (cond-> bank
            (:company-binding bank)
            (update :company-binding plain-company))))

(defn Bank->pb
  "Serialise a Bank map to protobuf bytes.

  Args:
  - m: Bank map matching the generated schema."
  [m]
  (proto/->pb (bank/new-Bank m)))

(defn Bank->java
  "Parse a Bank map into the generated Java protobuf class.

  Args:
  - m: Bank map matching the generated schema."
  [m]
  (BankProto$Bank/parseFrom (Bank->pb m)))

(def ^:private party-unset
  {:display-name ""
   :external-reference ""
   :merged-into-party-id ""
   :activated-at 0
   :rejected-at 0
   :suspended-at 0
   :suspended-by nil
   :resumed-at 0
   :resumed-by nil
   :closed-at 0
   :closed-by nil
   :merged-at 0
   :merged-by nil
   :idempotency-key ""
   :updated-at 0})

(def ^:private party-actors
  [:created-by :suspended-by :resumed-by :closed-by :merged-by])

(defn pb->Party
  "Parse Party protobuf bytes into a Clojure map. Each optional field is
  present only when set, and every `_by` is a plain map."
  [input]
  (reduce plain-embedded
          (without-unset (party/pb->Party input) party-unset)
          party-actors))

(defn Party->pb
  "Serialise a Party map to protobuf bytes.

  Args:
  - m: Party map matching the generated schema."
  [m]
  (proto/->pb (party/new-Party m)))

(defn Party->java
  "Parse a Party map into the generated Java protobuf class.

  Args:
  - m: Party map matching the generated schema."
  [m]
  (PartyProto$Party/parseFrom (Party->pb m)))

(defn pb->Idv
  "Parse Idv protobuf bytes into a Clojure map, without an outcome's
  `_at` it was never given.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (idv/pb->Idv input)
                 {:accepted-at 0 :rejected-at 0 :cancelled-at 0 :updated-at 0}))

(defn Idv->pb
  "Serialise an Idv map to protobuf bytes.

  Args:
  - m: Idv map matching the generated schema."
  [m]
  (proto/->pb (idv/new-Idv m)))

(defn Idv->java
  "Parse an Idv map into the generated Java protobuf class.

  Args:
  - m: Idv map matching the generated schema."
  [m]
  (IdvProto$Idv/parseFrom (Idv->pb m)))

(defn pb->IdvSession
  "Parse IdvSession protobuf bytes into a Clojure map, without a
  `:hand-off` or `:failure-reason` it was never given. An embedded
  message is a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (reduce plain-embedded
          (without-unset (idv/pb->IdvSession input)
                         {:hand-off nil :failure-reason "" :updated-at 0})
          [:hand-off :created-by]))

(defn IdvSession->pb
  "Serialise an IdvSession map to protobuf bytes.

  Args:
  - m: IdvSession map matching the generated schema."
  [m]
  (proto/->pb (idv/new-IdvSession m)))

(defn IdvSession->java
  "Parse an IdvSession map into the generated Java protobuf class.

  Args:
  - m: IdvSession map matching the generated schema."
  [m]
  (IdvSessionProto$IdvSession/parseFrom (IdvSession->pb m)))

(defn pb->CashAccountMigration
  "Parse CashAccountMigration protobuf bytes into a Clojure map, an
  `updated-at` present only when set.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (cash-account-migration/pb->CashAccountMigration input)
                 {:updated-at 0}))

(defn CashAccountMigration->pb
  "Serialise a CashAccountMigration map to protobuf bytes.

  Args:
  - m: CashAccountMigration map matching the generated schema."
  [m]
  (proto/->pb (cash-account-migration/new-CashAccountMigration m)))

(defn CashAccountMigration->java
  "Parse a CashAccountMigration map into the generated Java protobuf
  class.

  Args:
  - m: CashAccountMigration map matching the generated schema."
  [m]
  (CashAccountMigrationProto$CashAccountMigration/parseFrom
   (CashAccountMigration->pb m)))

(def ^{:doc "Parse CashAccountMigrationRun protobuf bytes into a map."}
     pb->CashAccountMigrationRun
  cash-account-migration/pb->CashAccountMigrationRun)

(defn CashAccountMigrationRun->pb
  "Serialise a CashAccountMigrationRun map to protobuf bytes.

  Args:
  - m: CashAccountMigrationRun map matching the generated schema."
  [m]
  (proto/->pb (cash-account-migration/new-CashAccountMigrationRun m)))

(defn CashAccountMigrationRun->java
  "Parse a CashAccountMigrationRun map into the generated Java protobuf
  class.

  Args:
  - m: CashAccountMigrationRun map matching the generated schema."
  [m]
  (CashAccountMigrationRunProto$CashAccountMigrationRun/parseFrom
   (CashAccountMigrationRun->pb m)))

(def ^{:doc "Parse CashAccountMigrationAccountRun protobuf bytes into a map."}
     pb->CashAccountMigrationAccountRun
  cash-account-migration/pb->CashAccountMigrationAccountRun)

(defn CashAccountMigrationAccountRun->pb
  "Serialise a CashAccountMigrationAccountRun map to protobuf bytes.

  Args:
  - m: CashAccountMigrationAccountRun map matching the generated schema."
  [m]
  (proto/->pb (cash-account-migration/new-CashAccountMigrationAccountRun m)))

(defn CashAccountMigrationAccountRun->java
  "Parse a CashAccountMigrationAccountRun map into the generated Java
  protobuf class.

  Args:
  - m: CashAccountMigrationAccountRun map matching the generated schema."
  [m]
  (CashAccountMigrationAccountRunProto$CashAccountMigrationAccountRun/parseFrom
   (CashAccountMigrationAccountRun->pb m)))

(def ^{:doc "Map of CashAccountMigrationStatus label to protobuf int value."}
     cash-account-migration-status->int
  cash-account-migration/CashAccountMigrationStatus-label2val)

(def ^{:doc "Map of CashAccountMigrationOutcome label to protobuf int value."}
     cash-account-migration-outcome->int
  cash-account-migration/CashAccountMigrationOutcome-label2val)

(def ^{:doc "Map of CashAccountMigrationStatus protobuf int value to label."}
     int->cash-account-migration-status
  cash-account-migration/CashAccountMigrationStatus-val2label)

(def ^{:doc "Map of InterestRunKind label to protobuf int value."}
     interest-run-kind->int
  interest/InterestRunKind-label2val)

(def ^{:doc "Map of InterestRunKind protobuf int value to label."}
     int->interest-run-kind
  interest/InterestRunKind-val2label)

(def ^{:doc "Map of InterestRunStatus label to protobuf int value."}
     interest-run-status->int
  interest/InterestRunStatus-label2val)

(def ^{:doc "Map of InterestAccountRunStatus label to protobuf int value."}
     interest-account-run-status->int
  interest/InterestAccountRunStatus-label2val)

(def ^{:doc "Parse InterestRun protobuf bytes into a Clojure map."}
     pb->InterestRun
  interest/pb->InterestRun)

(defn InterestRun->pb
  "Serialise an InterestRun map to protobuf bytes.

  Args:
  - m: InterestRun map matching the generated schema."
  [m]
  (proto/->pb (interest/new-InterestRun m)))

(defn InterestRun->java
  "Parse an InterestRun map into the generated Java protobuf class.

  Args:
  - m: InterestRun map matching the generated schema."
  [m]
  (InterestRunProto$InterestRun/parseFrom (InterestRun->pb m)))

(defn pb->InterestAccountRun
  "Parse InterestAccountRun protobuf bytes into a Clojure map, an
  `updated-at` present only when set.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (interest/pb->InterestAccountRun input) {:updated-at 0}))

(defn InterestAccountRun->pb
  "Serialise an InterestAccountRun map to protobuf bytes.

  Args:
  - m: InterestAccountRun map matching the generated schema."
  [m]
  (proto/->pb (interest/new-InterestAccountRun m)))

(defn InterestAccountRun->java
  "Parse an InterestAccountRun map into the generated Java protobuf
  class.

  Args:
  - m: InterestAccountRun map matching the generated schema."
  [m]
  (InterestAccountRunProto$InterestAccountRun/parseFrom
   (InterestAccountRun->pb m)))

(def ^:private scheduler-job-unset
  {:last-run-at 0 :next-run-at 0 :updated-at 0 :updated-by nil})

(defn pb->SchedulerJob
  "Parse SchedulerJob protobuf bytes into a Clojure map. A run's
  `last-run-at` and `next-run-at`, and an edit's `updated-at` and
  `updated-by`, are present only when set, the actor a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (scheduler/pb->SchedulerJob input)
      (without-unset scheduler-job-unset)
      (plain-embedded :updated-by)))

(defn SchedulerJob->pb
  "Serialise a SchedulerJob map to protobuf bytes.

  Args:
  - m: SchedulerJob map matching the generated schema."
  [m]
  (proto/->pb (scheduler/new-SchedulerJob m)))

(defn SchedulerJob->java
  "Parse a SchedulerJob map into the generated Java protobuf class.

  Args:
  - m: SchedulerJob map matching the generated schema."
  [m]
  (SchedulerJobProto$SchedulerJob/parseFrom (SchedulerJob->pb m)))

(def ^:private scheduler-run-unset
  {:expected-end-at 0
   :completed-at 0
   :failed-at 0
   :created-by nil
   :updated-at 0
   :failure-reason ""})

(def ^:private scheduler-task-run-unset
  {:started-at 0 :finished-at 0 :failure-reason ""})

(defn pb->SchedulerRun
  "Parse SchedulerRun protobuf bytes into a Clojure map, each task a
  plain map. An optional field is present only when set: the expected
  end, the outcome's `_at`, a forced run's `created-by`, a failed run's
  `failure-reason`, and a task's timings and failure.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (scheduler/pb->SchedulerRun input)
      (without-unset scheduler-run-unset)
      (plain-embedded :created-by)
      (update :tasks
              (fn [tasks]
                (mapv (fn [task] (without-unset task scheduler-task-run-unset))
                      tasks)))))

(defn SchedulerRun->pb
  "Serialise a SchedulerRun map to protobuf bytes.

  Args:
  - m: SchedulerRun map matching the generated schema."
  [m]
  (proto/->pb (scheduler/new-SchedulerRun m)))

(defn SchedulerRun->java
  "Parse a SchedulerRun map into the generated Java protobuf class.

  Args:
  - m: SchedulerRun map matching the generated schema."
  [m]
  (SchedulerRunProto$SchedulerRun/parseFrom (SchedulerRun->pb m)))

(def ^:private cash-account-unset
  {:bban ""
   :rotation nil
   :refused-reason ""
   :opened-at 0
   :refused-at 0
   :suspended-at 0
   :suspended-by nil
   :resumed-at 0
   :resumed-by nil
   :close-requested-at 0
   :close-requested-by nil
   :closed-at 0
   :address-rotated-at 0
   :address-rotated-by nil
   :updated-at 0})

(defn pb->CashAccount
  "Parse CashAccount protobuf bytes into a Clojure map. Strips the
  optional fields an account was never given, as they deserialise as
  proto2 defaults: an empty `bban` or `refused-reason`, no `rotation` or
  a rotation's empty `failed-reason`, and a transition's zero `_at` and
  absent `_by`. Downstream
  read sites use `(when (:bban account) ...)` to tell an account with
  addresses from one without. An embedded message is a plain map."
  [input]
  (let [account (reduce plain-embedded
                        (without-unset (cash-account/pb->CashAccount input)
                                       cash-account-unset)
                        [:rotation :created-by :suspended-by :resumed-by
                         :close-requested-by :address-rotated-by])]
    (cond-> account
            (:rotation account)
            (update :rotation without-unset {:failed-reason ""}))))

(defn CashAccount->pb
  "Serialise a CashAccount map to protobuf bytes.

  Args:
  - m: CashAccount map matching the generated schema."
  [m]
  (proto/->pb (cash-account/new-CashAccount m)))

(defn CashAccount->java
  "Parse a CashAccount map into the generated Java protobuf class.

  Args:
  - m: CashAccount map matching the generated schema."
  [m]
  (CashAccountProto$CashAccount/parseFrom (CashAccount->pb m)))

(defn pb->LedgerAccount
  "Parse LedgerAccount protobuf bytes into a Clojure map, an `updated-at`
  present only when set.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (ledger-account/pb->LedgerAccount input) {:updated-at 0}))

(defn LedgerAccount->pb
  "Serialise a LedgerAccount map to protobuf bytes.

  Args:
  - m: LedgerAccount map matching the generated schema."
  [m]
  (proto/->pb (ledger-account/new-LedgerAccount m)))

(defn LedgerAccount->java
  "Parse a LedgerAccount map into the generated Java protobuf class.

  Args:
  - m: LedgerAccount map matching the generated schema."
  [m]
  (LedgerAccountProto$LedgerAccount/parseFrom (LedgerAccount->pb m)))

(def ^:private inbound-payment-unset
  {:creditor-account-id ""
   :debtor-name ""
   :reference ""
   :transaction-id ""
   :suspended-reason-code ""
   :suspended-reason ""
   :return-failed-reason ""
   :admitted-at 0
   :held-at 0
   :settled-at 0
   :suspended-at 0
   :returned-at 0
   :return-failed-at 0
   :updated-at 0})

(defn pb->InboundPayment
  "Parse InboundPayment protobuf bytes into a Clojure map. Each optional
  field is present only when set: a suspended inbound credits no account,
  a held or admitted one posts no transaction, the scheme supplies
  `debtor-name` and `reference` only when the debtor's bank sent them,
  each state's `_at` only once the payment reached it, a suspended one's
  reason code and reason, and a failed return's reason.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (payment/pb->InboundPayment input) inbound-payment-unset))

(defn InboundPayment->pb
  "Serialise an InboundPayment map to protobuf bytes.

  Args:
  - m: InboundPayment map matching the generated schema."
  [m]
  (proto/->pb (payment/new-InboundPayment m)))

(defn InboundPayment->java
  "Parse an InboundPayment map into the generated Java protobuf
  class.

  Args:
  - m: InboundPayment map matching the generated schema."
  [m]
  (InboundPaymentProto$InboundPayment/parseFrom
   (InboundPayment->pb m)))

(def ^{:doc "Map of InboundPaymentStatus label to protobuf int value."}
     inbound-payment-status->int
  payment/InboundPaymentStatus-label2val)

(def ^:private outbound-payment-unset
  {:reference ""
   :failed-kind :outbound-payment-failed-kind-unknown
   :failed-reason-code ""
   :failed-reason ""
   :returned-reason-code ""
   :returned-reason ""
   :held-at 0
   :completed-at 0
   :failed-at 0
   :returned-at 0
   :updated-at 0})

(defn pb->OutboundPayment
  "Parse OutboundPayment protobuf bytes into a Clojure map. Each optional
  field is present only when set: a `reference` the caller gave, an
  outcome's `_at`, and a failed or returned payment's kind, code and
  reason. `created-by` is a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (payment/pb->OutboundPayment input)
      (without-unset outbound-payment-unset)
      (plain-embedded :created-by)))

(defn OutboundPayment->pb
  "Serialise an OutboundPayment map to protobuf bytes.

  Args:
  - m: OutboundPayment map matching the generated schema."
  [m]
  (proto/->pb (payment/new-OutboundPayment m)))

(defn OutboundPayment->java
  "Parse an OutboundPayment map into the generated Java protobuf
  class.

  Args:
  - m: OutboundPayment map matching the generated schema."
  [m]
  (OutboundPaymentProto$OutboundPayment/parseFrom
   (OutboundPayment->pb m)))

(def ^{:doc "Map of OutboundPaymentStatus label to protobuf int value."}
     outbound-payment-status->int
  payment/OutboundPaymentStatus-label2val)

(defn pb->InternalPayment
  "Parse InternalPayment protobuf bytes into a Clojure map, `reference`
  present only on a transfer the caller gave one and `created-by` a
  plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (payment/pb->InternalPayment input)
      (without-unset {:reference ""})
      (plain-embedded :created-by)))

(defn InternalPayment->pb
  "Serialise an InternalPayment map to protobuf bytes.

  Args:
  - m: InternalPayment map matching the generated schema."
  [m]
  (proto/->pb (payment/new-InternalPayment m)))

(defn InternalPayment->java
  "Parse an InternalPayment map into the generated Java protobuf
  class.

  Args:
  - m: InternalPayment map matching the generated schema."
  [m]
  (InternalPaymentProto$InternalPayment/parseFrom
   (InternalPayment->pb m)))

(defn pb->Transaction
  "Parse Transaction protobuf bytes into a Clojure map, without an
  unset `reference`."
  [input]
  (without-unset (transaction/pb->Transaction input) {:reference ""}))

(defn Transaction->pb
  "Serialise a Transaction map to protobuf bytes.

  Args:
  - m: Transaction map matching the generated schema."
  [m]
  (proto/->pb (transaction/new-Transaction m)))

(defn Transaction->java
  "Parse a Transaction map into the generated Java protobuf class.

  Args:
  - m: Transaction map matching the generated schema."
  [m]
  (TransactionProto$Transaction/parseFrom (Transaction->pb m)))

(def ^{:doc "Parse TransactionLeg protobuf bytes into a Clojure
  map."}
     pb->TransactionLeg
  transaction/pb->TransactionLeg)

(defn TransactionLeg->pb
  "Serialise a TransactionLeg map to protobuf bytes.

  Args:
  - m: TransactionLeg map matching the generated schema."
  [m]
  (proto/->pb (transaction/new-TransactionLeg m)))

(defn TransactionLeg->java
  "Parse a TransactionLeg map into the generated Java protobuf
  class.

  Args:
  - m: TransactionLeg map matching the generated schema."
  [m]
  (TransactionLegProto$TransactionLeg/parseFrom (TransactionLeg->pb m)))

(defn pb->PayeeCheck
  "Parse PayeeCheck protobuf bytes into a Clojure map, `created-by` a
  plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (plain-embedded (payee-check/pb->PayeeCheck input) :created-by))

(defn PayeeCheck->pb
  "Serialise a PayeeCheck map to protobuf bytes.

  Args:
  - m: PayeeCheck map matching the generated schema."
  [m]
  (proto/->pb (payee-check/new-PayeeCheck m)))

(defn PayeeCheck->java
  "Parse a PayeeCheck map into the generated Java protobuf class.

  Args:
  - m: PayeeCheck map matching the generated schema."
  [m]
  (PayeeCheckProto$PayeeCheck/parseFrom (PayeeCheck->pb m)))

(def
  ^{:doc
    "Parse ChangelogEvent protobuf bytes into a Clojure map.
  This is the shared changelog envelope every relayed store writes, so
  the relay decodes one message type regardless of the domain."}
  pb->ChangelogEvent
  changelog/pb->ChangelogEvent)

(defn ChangelogEvent->pb
  "Serialise a ChangelogEvent map to protobuf bytes.

  Args:
  - m: a map with `:event-id`, `:dedup-key`, `:event-name`,
    `:payload` (Avro-serialised bytes), `:correlation-id`,
    `:causation-id`, `:created-at`."
  [m]
  (proto/->pb (changelog/new-ChangelogEvent m)))

(def ^{:doc "Parse ClearbankOutboxEvent protobuf bytes into a Clojure map."}
     pb->ClearbankOutboxEvent
  clearbank/pb->ClearbankOutboxEvent)

(defn ClearbankOutboxEvent->pb
  "Serialise a ClearbankOutboxEvent map to protobuf bytes.

  Args:
  - m: ClearbankOutboxEvent map matching the generated schema."
  [m]
  (proto/->pb (clearbank/new-ClearbankOutboxEvent m)))

(defn ClearbankOutboxEvent->java
  "Parse a ClearbankOutboxEvent map into the generated Java protobuf class.

  Args:
  - m: ClearbankOutboxEvent map matching the generated schema."
  [m]
  (ClearbankOutboxEventProto$ClearbankOutboxEvent/parseFrom
   (ClearbankOutboxEvent->pb m)))

(def ^{:doc "Parse ClearbankOutboundIntent protobuf bytes into a Clojure map."}
     pb->ClearbankOutboundIntent
  clearbank/pb->ClearbankOutboundIntent)

(defn ClearbankOutboundIntent->pb
  "Serialise a ClearbankOutboundIntent map to protobuf bytes.

  Args:
  - m: ClearbankOutboundIntent map matching the generated schema."
  [m]
  (proto/->pb (clearbank/new-ClearbankOutboundIntent m)))

(defn ClearbankOutboundIntent->java
  "Parse a ClearbankOutboundIntent map into the generated Java protobuf class.

  Args:
  - m: ClearbankOutboundIntent map matching the generated schema."
  [m]
  (ClearbankOutboundIntentProto$ClearbankOutboundIntent/parseFrom
   (ClearbankOutboundIntent->pb m)))

(def ^{:doc "Parse OnfidoOutboxEvent protobuf bytes into a Clojure map."}
     pb->OnfidoOutboxEvent
  onfido/pb->OnfidoOutboxEvent)

(defn OnfidoOutboxEvent->pb
  "Serialise an OnfidoOutboxEvent map to protobuf bytes."
  [m]
  (proto/->pb (onfido/new-OnfidoOutboxEvent m)))

(defn OnfidoOutboxEvent->java
  "Parse an OnfidoOutboxEvent map into the generated Java protobuf class."
  [m]
  (OnfidoOutboxEventProto$OnfidoOutboxEvent/parseFrom (OnfidoOutboxEvent->pb
                                                       m)))

(def ^{:doc "Parse OnfidoOutboundIntent protobuf bytes into a Clojure map."}
     pb->OnfidoOutboundIntent
  onfido/pb->OnfidoOutboundIntent)

(defn OnfidoOutboundIntent->pb
  "Serialise an OnfidoOutboundIntent map to protobuf bytes."
  [m]
  (proto/->pb (onfido/new-OnfidoOutboundIntent m)))

(defn OnfidoOutboundIntent->java
  "Parse an OnfidoOutboundIntent map into the generated Java protobuf class."
  [m]
  (OnfidoOutboundIntentProto$OnfidoOutboundIntent/parseFrom
   (OnfidoOutboundIntent->pb m)))

(def ^{:doc "Parse ZypheOutboxEvent protobuf bytes into a Clojure map."}
     pb->ZypheOutboxEvent
  zyphe/pb->ZypheOutboxEvent)

(defn ZypheOutboxEvent->pb
  "Serialise a ZypheOutboxEvent map to protobuf bytes."
  [m]
  (proto/->pb (zyphe/new-ZypheOutboxEvent m)))

(defn ZypheOutboxEvent->java
  "Parse a ZypheOutboxEvent map into the generated Java protobuf class."
  [m]
  (ZypheOutboxEventProto$ZypheOutboxEvent/parseFrom (ZypheOutboxEvent->pb m)))

(def ^{:doc "Parse ZypheOutboundIntent protobuf bytes into a Clojure map."}
     pb->ZypheOutboundIntent
  zyphe/pb->ZypheOutboundIntent)

(defn ZypheOutboundIntent->pb
  "Serialise a ZypheOutboundIntent map to protobuf bytes."
  [m]
  (proto/->pb (zyphe/new-ZypheOutboundIntent m)))

(defn ZypheOutboundIntent->java
  "Parse a ZypheOutboundIntent map into the generated Java protobuf class."
  [m]
  (ZypheOutboundIntentProto$ZypheOutboundIntent/parseFrom
   (ZypheOutboundIntent->pb m)))

(def ^{:doc "Parse ModulrOutboxEvent protobuf bytes into a Clojure map."}
     pb->ModulrOutboxEvent
  modulr/pb->ModulrOutboxEvent)

(defn ModulrOutboxEvent->pb
  "Serialise a ModulrOutboxEvent map to protobuf bytes."
  [m]
  (proto/->pb (modulr/new-ModulrOutboxEvent m)))

(defn ModulrOutboxEvent->java
  "Parse a ModulrOutboxEvent map into the generated Java protobuf class."
  [m]
  (ModulrOutboxEventProto$ModulrOutboxEvent/parseFrom (ModulrOutboxEvent->pb
                                                       m)))

(def ^{:doc "Parse ModulrOutboundIntent protobuf bytes into a Clojure map."}
     pb->ModulrOutboundIntent
  modulr/pb->ModulrOutboundIntent)

(defn ModulrOutboundIntent->pb
  "Serialise a ModulrOutboundIntent map to protobuf bytes."
  [m]
  (proto/->pb (modulr/new-ModulrOutboundIntent m)))

(defn ModulrOutboundIntent->java
  "Parse a ModulrOutboundIntent map into the generated Java protobuf class."
  [m]
  (ModulrOutboundIntentProto$ModulrOutboundIntent/parseFrom
   (ModulrOutboundIntent->pb m)))

(def ^{:doc "Parse Form3OutboxEvent protobuf bytes into a Clojure map."}
     pb->Form3OutboxEvent
  form3/pb->Form3OutboxEvent)

(defn Form3OutboxEvent->pb
  "Serialise a Form3OutboxEvent map to protobuf bytes."
  [m]
  (proto/->pb (form3/new-Form3OutboxEvent m)))

(defn Form3OutboxEvent->java
  "Parse a Form3OutboxEvent map into the generated Java protobuf class."
  [m]
  (Form3OutboxEventProto$Form3OutboxEvent/parseFrom (Form3OutboxEvent->pb m)))

(def ^{:doc "Parse Form3OutboundIntent protobuf bytes into a Clojure map."}
     pb->Form3OutboundIntent
  form3/pb->Form3OutboundIntent)

(defn Form3OutboundIntent->pb
  "Serialise a Form3OutboundIntent map to protobuf bytes."
  [m]
  (proto/->pb (form3/new-Form3OutboundIntent m)))

(defn Form3OutboundIntent->java
  "Parse a Form3OutboundIntent map into the generated Java protobuf class."
  [m]
  (Form3OutboundIntentProto$Form3OutboundIntent/parseFrom
   (Form3OutboundIntent->pb m)))

(def ^:private payment-provider-transfer-unset
  {:debtor-account-id ""
   :failed-reason ""
   :completed-at 0
   :failed-at 0
   :updated-at 0})

(defn pb->PaymentProviderTransfer
  "Parse PaymentProviderTransfer protobuf bytes into a Clojure map. Each
  optional field is present only when set: a debtor where the money came
  from inside the provider, and an outcome's `_at` and a failed one's
  reason.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (payment/pb->PaymentProviderTransfer input)
                 payment-provider-transfer-unset))

(defn PaymentProviderTransfer->pb
  "Serialise a PaymentProviderTransfer map to protobuf bytes.

  Args:
  - m: PaymentProviderTransfer map matching the generated schema."
  [m]
  (proto/->pb (payment/new-PaymentProviderTransfer m)))

(defn PaymentProviderTransfer->java
  "Parse a PaymentProviderTransfer map into the generated Java protobuf
  class.

  Args:
  - m: PaymentProviderTransfer map matching the generated schema."
  [m]
  (PaymentProviderTransferProto$PaymentProviderTransfer/parseFrom
   (PaymentProviderTransfer->pb m)))

(def ^:private policy-unset
  {:description "" :archived-at 0 :archived-by nil :updated-at 0})

(defn pb->Policy
  "Parse Policy protobuf bytes into a Clojure map, a `description`,
  `archived-at`, `archived-by` and `updated-at` present only when set,
  and `archived-by` a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (policy/pb->Policy input)
      (without-unset policy-unset)
      (plain-embedded :archived-by)))

(defn Policy->pb
  "Serialise a Policy map to protobuf bytes.

  Args:
  - m: Policy map matching the generated schema."
  [m]
  (proto/->pb (policy/new-Policy m)))

(defn Policy->java
  "Parse a Policy map into the generated Java protobuf class.

  Args:
  - m: Policy map matching the generated schema."
  [m]
  (PolicyProto$Policy/parseFrom (Policy->pb m)))

(defn pb->PolicyBinding
  "Parse PolicyBinding protobuf bytes into a Clojure map, a `reason`
  present only when set and `created-by` a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (policy/pb->PolicyBinding input)
      (without-unset {:reason ""})
      (plain-embedded :created-by)))

(defn PolicyBinding->pb
  "Serialise a PolicyBinding map to protobuf bytes.

  Args:
  - m: PolicyBinding map matching the generated schema."
  [m]
  (proto/->pb (policy/new-PolicyBinding m)))

(defn PolicyBinding->java
  "Parse a PolicyBinding map into the generated Java protobuf
  class.

  Args:
  - m: PolicyBinding map matching the generated schema."
  [m]
  (PolicyBindingProto$PolicyBinding/parseFrom (PolicyBinding->pb m)))

(defn pb->User
  "Parse User protobuf bytes into a Clojure map, without an unset
  `avatar-url` or `updated-at`."
  [input]
  (without-unset (user/pb->User input) {:avatar-url "" :updated-at 0}))

(defn User->pb
  "Serialise a User map to protobuf bytes.

  Args:
  - m: User map matching the generated schema."
  [m]
  (proto/->pb (user/new-User m)))

(defn User->java
  "Parse a User map into the generated Java protobuf class.

  Args:
  - m: User map matching the generated schema."
  [m]
  (UserProto$User/parseFrom (User->pb m)))

(def ^{:doc "Map of IdentityProvider label to protobuf int value."}
     identity-provider->int
  user/IdentityProvider-label2val)

(defn identity-provider->pb-enum
  "Convert an identity-provider keyword to the protobuf enum value,
  for use in FDB index queries.

  Args:
  - identity-provider: `:identity-provider-*` keyword."
  [identity-provider]
  (UserProto$IdentityProvider/forNumber
   (identity-provider->int identity-provider)))

(def ^:private member-unset
  {:removed-at 0
   :removed-by nil
   :removed-reason ""
   :left-at 0
   :left-by nil
   :invitation-id ""
   :updated-at 0})

(defn pb->Member
  "Parse Member protobuf bytes into a Clojure map. A removal's or a
  leave's `_at`, `_by` and reason, and `invitation-id`, are present only
  when set, and every actor is a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (member/pb->Member input)
      (without-unset member-unset)
      (plain-embedded :created-by)
      (plain-embedded :removed-by)
      (plain-embedded :left-by)))

(defn Member->pb
  "Serialise a Member map to protobuf bytes.

  Args:
  - m: Member map matching the generated schema."
  [m]
  (proto/->pb (member/new-Member m)))

(defn Member->java
  "Parse a Member map into the generated Java protobuf class.

  Args:
  - m: Member map matching the generated schema."
  [m]
  (MemberProto$Member/parseFrom (Member->pb m)))

(def ^{:doc "Map of Member Role label to protobuf int value."} role->int
  member/Role-label2val)

(defn role->pb-enum
  "Convert a member role keyword to the protobuf enum value,
  for use in FDB index queries.

  Args:
  - role: `:role-*` keyword."
  [role]
  (MemberProto$Role/forNumber (role->int role)))

(def ^{:doc "Map of MemberStatus label to protobuf int value."}
     member-status->int
  member/MemberStatus-label2val)

(defn member-status->pb-enum
  "Convert a member-status keyword to the protobuf enum value, for
  use in FDB index queries.

  Args:
  - member-status: `:member-status-*` keyword."
  [member-status]
  (MemberProto$MemberStatus/forNumber
   (member-status->int member-status)))

(def ^{:doc "Map of ActorKind label to protobuf int value."} actor-kind->int
  actor/ActorKind-label2val)

(defn actor-kind->pb-enum
  "Convert an actor-kind keyword to the protobuf enum value, for use in
  FDB index queries.

  Args:
  - actor-kind: `:actor-kind-*` keyword."
  [actor-kind]
  (ActorProto$ActorKind/forNumber (actor-kind->int actor-kind)))

(def ^:private invitation-unset
  {:reason ""
   :withdrawn-reason ""
   :accepted-at 0
   :accepted-by nil
   :declined-at 0
   :declined-by nil
   :withdrawn-at 0
   :withdrawn-by nil
   :resent-at 0
   :resent-by nil
   :updated-at 0})

(def ^:private invitation-actors
  [:created-by :accepted-by :declined-by :withdrawn-by :resent-by])

(defn pb->Invitation
  "Parse Invitation protobuf bytes into a Clojure map. The reasons and
  each transition's `_at` and `_by` are present only when set, and every
  `_by` is a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (reduce plain-embedded
          (without-unset (member/pb->Invitation input) invitation-unset)
          invitation-actors))

(defn Invitation->pb
  "Serialise an Invitation map to protobuf bytes.

  Args:
  - m: Invitation map matching the generated schema."
  [m]
  (proto/->pb (member/new-Invitation m)))

(defn Invitation->java
  "Parse an Invitation map into the generated Java protobuf class.

  Args:
  - m: Invitation map matching the generated schema."
  [m]
  (InvitationProto$Invitation/parseFrom (Invitation->pb m)))

(def ^{:doc "Map of InvitationStatus label to protobuf int value."}
     invitation-status->int
  member/InvitationStatus-label2val)

(defn invitation-status->pb-enum
  "Convert an invitation-status keyword to the protobuf enum value, for
  use in FDB index queries.

  Args:
  - invitation-status: `:invitation-status-*` keyword."
  [invitation-status]
  (InvitationProto$InvitationStatus/forNumber
   (invitation-status->int invitation-status)))

(defn pb->MemberRoleChange
  "Parse MemberRoleChange protobuf bytes into a Clojure map. `reason`
  is present only when set, and `created-by` is a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (member/pb->MemberRoleChange input)
      (without-unset {:reason ""})
      (plain-embedded :created-by)))

(defn MemberRoleChange->pb
  "Serialise a MemberRoleChange map to protobuf bytes.

  Args:
  - m: MemberRoleChange map matching the generated schema."
  [m]
  (proto/->pb (member/new-MemberRoleChange m)))

(defn MemberRoleChange->java
  "Parse a MemberRoleChange map into the generated Java protobuf
  class.

  Args:
  - m: MemberRoleChange map matching the generated schema."
  [m]
  (MemberRoleChangeProto$MemberRoleChange/parseFrom
   (MemberRoleChange->pb m)))

(def ^:private webhook-endpoint-unset
  {:description ""
   :previous-secret ""
   :previous-secret-expires-at 0
   :rotation-idempotency-key ""
   :disabled-at 0
   :disabled-by nil
   :enabled-at 0
   :enabled-by nil
   :removed-at 0
   :removed-by nil
   :secret-rotated-at 0
   :secret-rotated-by nil
   :updated-at 0
   :updated-by nil})

(defn pb->WebhookEndpoint
  "Parse WebhookEndpoint protobuf bytes into a Clojure map, without its
  unset optional fields, and each actor a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (reduce plain-embedded
          (without-unset (webhook/pb->WebhookEndpoint input)
                         webhook-endpoint-unset)
          [:created-by :disabled-by :enabled-by :removed-by :secret-rotated-by
           :updated-by]))

(defn WebhookEndpoint->pb
  "Serialise a WebhookEndpoint map to protobuf bytes.

  Args:
  - m: WebhookEndpoint map matching the generated schema."
  [m]
  (proto/->pb (webhook/new-WebhookEndpoint m)))

(defn WebhookEndpoint->java
  "Parse a WebhookEndpoint map into the generated Java protobuf class.

  Args:
  - m: WebhookEndpoint map matching the generated schema."
  [m]
  (WebhookEndpointProto$WebhookEndpoint/parseFrom (WebhookEndpoint->pb m)))

(defn pb->WebhookNotification
  "Parse WebhookNotification protobuf bytes into a Clojure map, without
  its unset optional fields.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset
   (webhook/pb->WebhookNotification input)
   {:status-before "" :status-after "" :resource-idempotency-key ""}))

(defn WebhookNotification->pb
  "Serialise a WebhookNotification map to protobuf bytes.

  Args:
  - m: WebhookNotification map matching the generated schema."
  [m]
  (proto/->pb (webhook/new-WebhookNotification m)))

(defn WebhookNotification->java
  "Parse a WebhookNotification map into the generated Java protobuf class.

  Args:
  - m: WebhookNotification map matching the generated schema."
  [m]
  (WebhookNotificationProto$WebhookNotification/parseFrom
   (WebhookNotification->pb m)))

(defn pb->WebhookDelivery
  "Parse WebhookDelivery protobuf bytes into a Clojure map, without its
  unset optional fields, and `created-by` a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (webhook/pb->WebhookDelivery input)
      (without-unset {:delivered-at 0
                      :failed-at 0
                      :created-by nil
                      :next-attempt-at 0
                      :traceparent ""})
      (plain-embedded :created-by)))

(defn WebhookDelivery->pb
  "Serialise a WebhookDelivery map to protobuf bytes.

  Args:
  - m: WebhookDelivery map matching the generated schema."
  [m]
  (proto/->pb (webhook/new-WebhookDelivery m)))

(defn WebhookDelivery->java
  "Parse a WebhookDelivery map into the generated Java protobuf class.

  Args:
  - m: WebhookDelivery map matching the generated schema."
  [m]
  (WebhookDeliveryProto$WebhookDelivery/parseFrom (WebhookDelivery->pb m)))

(defn pb->WebhookDeliveryAttempt
  "Parse WebhookDeliveryAttempt protobuf bytes into a Clojure map,
  without an unset `response-status` or `failed-reason`.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (webhook/pb->WebhookDeliveryAttempt input)
                 {:response-status 0 :failed-reason ""}))

(defn WebhookDeliveryAttempt->pb
  "Serialise a WebhookDeliveryAttempt map to protobuf bytes.

  Args:
  - m: WebhookDeliveryAttempt map matching the generated schema."
  [m]
  (proto/->pb (webhook/new-WebhookDeliveryAttempt m)))

(defn WebhookDeliveryAttempt->java
  "Parse a WebhookDeliveryAttempt map into the generated Java protobuf class.

  Args:
  - m: WebhookDeliveryAttempt map matching the generated schema."
  [m]
  (WebhookDeliveryAttemptProto$WebhookDeliveryAttempt/parseFrom
   (WebhookDeliveryAttempt->pb m)))

(def ^{:doc "Map of WebhookEndpointStatus label to protobuf int value."}
     webhook-endpoint-status->int
  webhook/WebhookEndpointStatus-label2val)

(def ^{:doc "Map of WebhookDeliveryStatus label to protobuf int value."}
     webhook-delivery-status->int
  webhook/WebhookDeliveryStatus-label2val)

(def ^:private email-delivery-unset
  {:message-id ""
   :sent-at 0
   :next-attempt-at 0
   :traceparent ""
   :failure-reason ""
   :updated-at 0})

(defn pb->EmailDelivery
  "Parse EmailDelivery protobuf bytes into a Clojure map. Each optional
  field is present only when set.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (email/pb->EmailDelivery input) email-delivery-unset))

(defn EmailDelivery->pb
  "Serialise an EmailDelivery map to protobuf bytes.

  Args:
  - m: EmailDelivery map matching the generated schema."
  [m]
  (proto/->pb (email/new-EmailDelivery m)))

(defn EmailDelivery->java
  "Parse an EmailDelivery map into the generated Java protobuf class.

  Args:
  - m: EmailDelivery map matching the generated schema."
  [m]
  (EmailDeliveryProto$EmailDelivery/parseFrom (EmailDelivery->pb m)))

(def ^{:doc "Map of EmailDeliveryStatus label to protobuf int value."}
     email-delivery-status->int
  email/EmailDeliveryStatus-label2val)

(def ^{:doc "Map of EmailKind label to protobuf int value."} email-kind->int
  email/EmailKind-label2val)

(def ^:private circuit-breaker-unset
  {:next-probe-at 0 :cool-down-ms 0 :opened-at 0 :updated-at 0})

(defn pb->CircuitBreaker
  "Parse CircuitBreaker protobuf bytes into a Clojure map. `next-probe-at`,
  `cool-down-ms` and `opened-at` are present only when set.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (circuit-breaker/pb->CircuitBreaker input)
                 circuit-breaker-unset))

(defn CircuitBreaker->pb
  "Serialise a CircuitBreaker map to protobuf bytes.

  Args:
  - m: CircuitBreaker map matching the generated schema."
  [m]
  (proto/->pb (circuit-breaker/new-CircuitBreaker m)))

(defn CircuitBreaker->java
  "Parse a CircuitBreaker map into the generated Java protobuf class.

  Args:
  - m: CircuitBreaker map matching the generated schema."
  [m]
  (CircuitBreakerProto$CircuitBreaker/parseFrom (CircuitBreaker->pb m)))

(def ^:private account-reward-unset
  {:transaction-id ""
   :deferred-reason ""
   :deferred-at 0
   :paid-at 0
   :updated-at 0})

(defn pb->AccountReward
  "Parse AccountReward protobuf bytes into a Clojure map. A paid one's
  `transaction-id` and `paid-at`, a deferred one's `deferred-reason` and
  `deferred-at`, and `updated-at` are present only when set.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (reward/pb->AccountReward input) account-reward-unset))

(defn AccountReward->pb
  "Serialise an AccountReward map to protobuf bytes.

  Args:
  - m: AccountReward map matching the generated schema."
  [m]
  (proto/->pb (reward/new-AccountReward m)))

(defn AccountReward->java
  "Parse an AccountReward map into the generated Java protobuf class.

  Args:
  - m: AccountReward map matching the generated schema."
  [m]
  (AccountRewardProto$AccountReward/parseFrom (AccountReward->pb m)))

(def ^{:doc "Map of RewardKind label to protobuf int value."} reward-kind->int
  cash-account-product/RewardKind-label2val)

(def ^{:doc "Map of AccountRewardStatus label to protobuf int value."}
     account-reward-status->int
  reward/AccountRewardStatus-label2val)
