(ns com.repldriven.queenswood.schema.interface
  "Bank-specific protobuf schema bridge. Wraps the generated
  `com.repldriven.queenswood.schemas.*` namespaces with EDN-friendly
  converters: `pb->X` parses bytes into a Clojure map, `X->pb`
  serialises a map to bytes, and `X->java` parses bytes into the
  generated Java class. Also exposes enum-label converters used by
  FDB index queries."
  (:require
    [com.repldriven.queenswood.schemas.balances :as balances]
    [com.repldriven.queenswood.schemas.banks :as banks]
    [com.repldriven.queenswood.schemas.cash_account_migrations :as
     cash-account-migrations]
    [com.repldriven.queenswood.schemas.cash_account_products :as
     cash-account-products]
    [com.repldriven.queenswood.schemas.cash_accounts :as cash-accounts]
    [com.repldriven.queenswood.schemas.changelog :as changelog]
    [com.repldriven.queenswood.schemas.clearbank :as clearbank]
    [com.repldriven.queenswood.schemas.company :as company]
    [com.repldriven.queenswood.schemas.emails :as emails]
    [com.repldriven.queenswood.schemas.idempotency :as idempotency]
    [com.repldriven.queenswood.schemas.idv :as idv]
    [com.repldriven.queenswood.schemas.interest :as interest]
    [com.repldriven.queenswood.schemas.ledger_accounts :as ledger-accounts]
    [com.repldriven.queenswood.schemas.memberships :as memberships]
    [com.repldriven.queenswood.schemas.form3 :as form3]
    [com.repldriven.queenswood.schemas.modulr :as modulr]
    [com.repldriven.queenswood.schemas.onfido :as onfido]
    [com.repldriven.queenswood.schemas.outbound :as outbound]
    [com.repldriven.queenswood.schemas.party :as party]
    [com.repldriven.queenswood.schemas.payee_check :as payee-check]
    [com.repldriven.queenswood.schemas.payments :as payments]
    [com.repldriven.queenswood.schemas.person_identification :as
     person-identification]
    [com.repldriven.queenswood.schemas.policies :as policies]
    [com.repldriven.queenswood.schemas.rewards :as rewards]
    [com.repldriven.queenswood.schemas.scheduler :as scheduler]
    [com.repldriven.queenswood.schemas.transactions :as transactions]
    [com.repldriven.queenswood.schemas.types :as types]
    [com.repldriven.queenswood.schemas.users :as users]
    [com.repldriven.queenswood.schemas.webhooks :as webhooks]
    [com.repldriven.queenswood.schemas.zyphe :as zyphe]

    [protojure.protobuf :as proto])
  (:import
    (com.repldriven.queenswood.schemas.balances
     AccountBalanceProto$AccountBalance)
    (com.repldriven.queenswood.schemas.cash_account_migrations
     CashAccountMigrationProto$CashAccountMigration
     CashAccountMigrationRunProto$CashAccountMigrationRun
     CashAccountMigrationAccountRunProto$CashAccountMigrationAccountRun)
    (com.repldriven.queenswood.schemas.cash_account_products
     CashAccountProductProto$CashAccountProduct
     CashAccountProductTemplateProto$CashAccountProductTemplate
     CashAccountProductTypesProto$IsoCashAccountType)
    (com.repldriven.queenswood.schemas.cash_accounts
     CashAccountProto$CashAccount)
    (com.repldriven.queenswood.schemas.company CompanyProto$Company)
    (com.repldriven.queenswood.schemas.emails
     EmailDeliveryProto$EmailDelivery)
    (com.repldriven.queenswood.schemas.idempotency IdempotencyProto$Idempotency)
    (com.repldriven.queenswood.schemas.idv IdvProto$Idv
                                           IdvSessionProto$IdvSession)
    (com.repldriven.queenswood.schemas.interest
     InterestRunProto$InterestRun
     InterestAccountRunProto$InterestAccountRun)
    (com.repldriven.queenswood.schemas.ledger_accounts
     LedgerAccountProto$LedgerAccount
     LedgerAccountProto$GlAccountCode)
    (com.repldriven.queenswood.schemas.scheduler
     SchedulerJobProto$SchedulerJob
     SchedulerRunProto$SchedulerRun)
    (com.repldriven.queenswood.schemas.banks BankProto$Bank)
    (com.repldriven.queenswood.schemas.party PartyProto$Party)
    (com.repldriven.queenswood.schemas.person_identification
     PersonNameProto$PersonName)
    (com.repldriven.queenswood.schemas.payments
     InboundPaymentProto$InboundPayment
     InternalPaymentProto$InternalPayment
     OutboundPaymentProto$OutboundPayment
     ProviderTransferProto$ProviderTransfer)
    (com.repldriven.queenswood.schemas.payee_check
     PayeeCheckProto$PayeeCheck)
    (com.repldriven.queenswood.schemas.clearbank
     ClearbankOutboxEventProto$ClearbankOutboxEvent
     ClearbankOutboundIntentProto$ClearbankOutboundIntent)
    (com.repldriven.queenswood.schemas.onfido
     OnfidoOutboxEventProto$OnfidoOutboxEvent
     OnfidoOutboundIntentProto$OnfidoOutboundIntent)
    (com.repldriven.queenswood.schemas.policies
     PolicyProto$Policy
     PolicyProto$PolicyBinding)
    (com.repldriven.queenswood.schemas.rewards RewardProto$Reward)
    (com.repldriven.queenswood.schemas.transactions
     TransactionProto$Transaction
     TransactionProto$TransactionLeg
     TransactionProto$TransactionType)
    (com.repldriven.queenswood.schemas.users
     UserProto$User
     UserProto$IdentityProvider
     UserProto$UserStatus)
    (com.repldriven.queenswood.schemas.memberships
     AccessEventProto$AccessEvent
     AccessEventProto$AccessEventKind
     ActorProto$ActorKind
     InvitationProto$Invitation
     InvitationProto$InvitationStatus
     MembershipProto$Membership
     MembershipProto$MembershipStatus
     MembershipProto$Role)
    (com.repldriven.queenswood.schemas.webhooks
     WebhookDeliveryProto$WebhookDelivery
     WebhookDeliveryAttemptProto$WebhookDeliveryAttempt
     WebhookEndpointProto$WebhookEndpoint
     WebhookNotificationProto$WebhookNotification)
    (com.repldriven.queenswood.schemas.outbound
     CircuitBreakerProto$CircuitBreaker
     OutboundTypesProto$OutboundIntentStatus)
    (com.repldriven.queenswood.schemas.zyphe
     ZypheOutboxEventProto$ZypheOutboxEvent
     ZypheOutboundIntentProto$ZypheOutboundIntent)
    (com.repldriven.queenswood.schemas.form3
     Form3OutboundIntentProto$Form3OutboundIntent
     Form3OutboxEventProto$Form3OutboxEvent)
    (com.repldriven.queenswood.schemas.modulr
     ModulrOutboxEventProto$ModulrOutboxEvent
     ModulrOutboundIntentProto$ModulrOutboundIntent)))

(def ^{:doc "Parse AccountBalance protobuf bytes into a Clojure map."}
     pb->AccountBalance
  balances/pb->AccountBalance)

(defn AccountBalance->pb
  "Serialise an AccountBalance map to protobuf bytes.

  Args:
  - m: AccountBalance map matching the generated schema."
  [m]
  (proto/->pb (balances/new-AccountBalance m)))

(defn AccountBalance->java
  "Parse an AccountBalance map into the generated Java protobuf class.

  Args:
  - m: AccountBalance map matching the generated schema."
  [m]
  (AccountBalanceProto$AccountBalance/parseFrom (AccountBalance->pb m)))

(def ^{:doc "Map of Balance type label to protobuf int value."}
     balance-type->int
  balances/BalanceType-label2val)

(def ^{:doc "Map of Balance status label to protobuf int value."}
     balance-status->int
  balances/BalanceStatus-label2val)

(def ^{:doc "Map of ProductType label to protobuf int value."} product-type->int
  types/ProductType-label2val)

(def ^{:doc "Map of ProductType protobuf int value to label."} int->product-type
  types/ProductType-val2label)

(def ^{:doc "Map of CashAccount AccountType label to protobuf int
  value."}
     account-type->int
  cash-accounts/AccountType-label2val)

(def ^{:doc "Map of IsoCashAccountType label to protobuf int value."}
     iso-cash-account-type->int
  cash-account-products/IsoCashAccountType-label2val)

(def ^{:doc "Map of CompanyRegistry label to protobuf int value."}
     company-registry->int
  company/CompanyRegistry-label2val)

(def
  ^{:doc
    "Map of GlAccountCode role label to protobuf int value — the
  chart number itself, e.g. :gl-account-code-suspense -> 2500."}
  gl-account-code->int
  ledger-accounts/GlAccountCode-label2val)

(def ^{:doc "Map of GlAccountCode protobuf int value to role label."}
     int->gl-account-code
  ledger-accounts/GlAccountCode-val2label)

(defn gl-account-code->pb-enum
  "Convert a gl-account-code role keyword to the protobuf enum value, for
  use as the comparand in an FDB enum-field index query."
  [gl-account-code]
  (LedgerAccountProto$GlAccountCode/forNumber
   (gl-account-code->int gl-account-code)))

(def ^{:doc "Map of OutboundIntentStatus label to protobuf int value."}
     outbound-intent-status->int
  outbound/OutboundIntentStatus-label2val)

(defn outbound-intent-status->pb-enum
  "Convert an outbound-intent-status keyword to the protobuf enum value,
  for use as the comparand in an FDB enum-field index query.

  Args:
  - outbound-intent-status: `:outbound-intent-status-*` keyword."
  [outbound-intent-status]
  (OutboundTypesProto$OutboundIntentStatus/forNumber
   (outbound-intent-status->int outbound-intent-status)))

(defn iso-cash-account-type->pb-enum
  "Convert an iso-cash-account-type keyword to the protobuf enum
  value, for use in FDB index queries.

  Args:
  - iso-cash-account-type: `:iso-cash-account-type-*` keyword."
  [iso-cash-account-type]
  (CashAccountProductTypesProto$IsoCashAccountType/forNumber
   (iso-cash-account-type->int iso-cash-account-type)))

(def transaction-type->int transactions/TransactionType-label2val)

(def ^{:doc "Map of LegSide label to protobuf int value."} leg-side->int
  transactions/LegSide-label2val)

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
  (let [version (cash-account-products/pb->CashAccountProduct input)]
    (cond->
     (reduce
      plain-embedded
      (update version :reward-terms (fn [terms] (mapv #(into {} %) terms)))
      [:created-by :published-by :discarded-by])

     (nil? (:interest-terms version))
     (dissoc :interest-terms)

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
  (proto/->pb (cash-account-products/new-CashAccountProduct m)))

(defn CashAccountProduct->java
  "Parse a CashAccountProduct map into the generated Java protobuf
  class.

  Args:
  - m: CashAccountProduct map matching the generated schema."
  [m]
  (CashAccountProductProto$CashAccountProduct/parseFrom
   (CashAccountProduct->pb m)))

(def ^{:doc "Parse CashAccountProductTemplate protobuf bytes into a map."}
     pb->CashAccountProductTemplate
  cash-account-products/pb->CashAccountProductTemplate)

(defn CashAccountProductTemplate->pb
  "Serialise a CashAccountProductTemplate map to protobuf bytes.

  Args:
  - m: CashAccountProductTemplate map matching the generated schema."
  [m]
  (proto/->pb (cash-account-products/new-CashAccountProductTemplate m)))

(defn CashAccountProductTemplate->java
  "Parse a CashAccountProductTemplate map into the generated Java
  protobuf class.

  Args:
  - m: CashAccountProductTemplate map matching the generated schema."
  [m]
  (CashAccountProductTemplateProto$CashAccountProductTemplate/parseFrom
   (CashAccountProductTemplate->pb m)))

(def ^:private company-unset
  {:jurisdiction "" :incorporated-on 0 :registered-office-address nil})

(def ^:private address-unset
  {:address-line-1 "" :locality "" :postal-code "" :country ""})

(defn pb->Company
  "Parse Company protobuf bytes into a Clojure map. Each optional field,
  and each line of the registered office address, is present only when
  set. The address is a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (let [company (without-unset (company/pb->Company input) company-unset)]
    (cond-> company
            (:registered-office-address company)
            (update :registered-office-address without-unset address-unset))))

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

(defn pb->Bank
  "Parse Bank protobuf bytes into a Clojure map. Strips `created-by`
  and `idempotency-key` when unset — a bank created before they were
  recorded leaves both unset, and one created by no command the
  second."
  [input]
  (let [bank (banks/pb->Bank input)]
    (cond-> bank
            (nil? (:created-by bank))
            (dissoc :created-by)

            (= "" (:idempotency-key bank))
            (dissoc :idempotency-key))))

(defn Bank->pb
  "Serialise a Bank map to protobuf bytes.

  Args:
  - m: Bank map matching the generated schema."
  [m]
  (proto/->pb (banks/new-Bank m)))

(defn Bank->java
  "Parse a Bank map into the generated Java protobuf class.

  Args:
  - m: Bank map matching the generated schema."
  [m]
  (BankProto$Bank/parseFrom (Bank->pb m)))

(defn pb->Party
  "Parse Party protobuf bytes into a Clojure map. Strips
  `merged-into-party-id`, `idempotency-key` and `external-reference`
  when they deserialise as the proto2 empty-string default — every
  party except a merged-away one leaves the first unset, every party
  not created by a client command the second, and every party
  registered without a reference the third."
  [input]
  (let [party (party/pb->Party input)]
    (cond-> party
            (= "" (:merged-into-party-id party))
            (dissoc :merged-into-party-id)

            (= "" (:idempotency-key party))
            (dissoc :idempotency-key)

            (= "" (:external-reference party))
            (dissoc :external-reference))))

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

(def ^{:doc "Parse PersonName protobuf bytes into a Clojure map."}
     pb->PersonName
  person-identification/pb->PersonName)

(defn PersonName->pb
  "Serialise a PersonName map to protobuf bytes.

  Args:
  - m: PersonName map matching the generated schema."
  [m]
  (proto/->pb (person-identification/new-PersonName m)))

(defn PersonName->java
  "Parse a PersonName map into the generated Java protobuf class.

  Args:
  - m: PersonName map matching the generated schema."
  [m]
  (PersonNameProto$PersonName/parseFrom (PersonName->pb m)))

(defn pb->Idv
  "Parse Idv protobuf bytes into a Clojure map, without a `:completed-at`
  or `:failure-reason` it was never given.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (idv/pb->Idv input) {:completed-at 0 :failure-reason ""}))

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
                         {:hand-off nil :failure-reason ""})
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

(def ^{:doc "Parse CashAccountMigration protobuf bytes into a Clojure map."}
     pb->CashAccountMigration
  cash-account-migrations/pb->CashAccountMigration)

(defn CashAccountMigration->pb
  "Serialise a CashAccountMigration map to protobuf bytes.

  Args:
  - m: CashAccountMigration map matching the generated schema."
  [m]
  (proto/->pb (cash-account-migrations/new-CashAccountMigration m)))

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
  cash-account-migrations/pb->CashAccountMigrationRun)

(defn CashAccountMigrationRun->pb
  "Serialise a CashAccountMigrationRun map to protobuf bytes.

  Args:
  - m: CashAccountMigrationRun map matching the generated schema."
  [m]
  (proto/->pb (cash-account-migrations/new-CashAccountMigrationRun m)))

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
  cash-account-migrations/pb->CashAccountMigrationAccountRun)

(defn CashAccountMigrationAccountRun->pb
  "Serialise a CashAccountMigrationAccountRun map to protobuf bytes.

  Args:
  - m: CashAccountMigrationAccountRun map matching the generated schema."
  [m]
  (proto/->pb (cash-account-migrations/new-CashAccountMigrationAccountRun m)))

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
  cash-account-migrations/CashAccountMigrationStatus-label2val)

(def ^{:doc "Map of CashAccountMigrationOutcome label to protobuf int value."}
     cash-account-migration-outcome->int
  cash-account-migrations/CashAccountMigrationOutcome-label2val)

(def ^{:doc "Map of CashAccountMigrationStatus protobuf int value to label."}
     int->cash-account-migration-status
  cash-account-migrations/CashAccountMigrationStatus-val2label)

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

(def ^{:doc "Parse InterestAccountRun protobuf bytes into a Clojure map."}
     pb->InterestAccountRun
  interest/pb->InterestAccountRun)

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

(def ^{:doc "Parse SchedulerJob protobuf bytes into a Clojure map."}
     pb->SchedulerJob
  scheduler/pb->SchedulerJob)

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

(def ^{:doc "Parse SchedulerRun protobuf bytes into a Clojure map."}
     pb->SchedulerRun
  scheduler/pb->SchedulerRun)

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
   :failure-reason ""
   :opened-at 0
   :suspended-at 0
   :suspended-by nil
   :resumed-at 0
   :resumed-by nil
   :closed-at 0
   :closed-by nil
   :rotated-at 0
   :rotated-by nil})

(defn pb->CashAccount
  "Parse CashAccount protobuf bytes into a Clojure map. Strips the
  optional fields an account was never given, as they deserialise as
  proto2 defaults: an empty `bban` or `failure-reason`, no `rotation` or
  a rotation's empty `failure-reason`, and a transition's zero `_at` and
  absent `_by`. Downstream
  read sites use `(when (:bban account) ...)` to tell an account with
  addresses from one without. An embedded message is a plain map."
  [input]
  (let [account (reduce plain-embedded
                        (without-unset (cash-accounts/pb->CashAccount input)
                                       cash-account-unset)
                        [:rotation :created-by :suspended-by :resumed-by
                         :closed-by :rotated-by])]
    (cond-> account
            (:rotation account)
            (update :rotation without-unset {:failure-reason ""}))))

(defn CashAccount->pb
  "Serialise a CashAccount map to protobuf bytes.

  Args:
  - m: CashAccount map matching the generated schema."
  [m]
  (proto/->pb (cash-accounts/new-CashAccount m)))

(defn CashAccount->java
  "Parse a CashAccount map into the generated Java protobuf class.

  Args:
  - m: CashAccount map matching the generated schema."
  [m]
  (CashAccountProto$CashAccount/parseFrom (CashAccount->pb m)))

(defn pb->LedgerAccount
  "Parse LedgerAccount protobuf bytes into a Clojure map, dropping the
  proto2 default `:sub-ledger-kind-unknown` emitted for an unset
  optional `sub_ledger_kind` so callers see `:sub-ledger-kind` only on
  control accounts that carry a real cohort.

  Args:
  - input: protobuf bytes."
  [input]
  (let [account (ledger-accounts/pb->LedgerAccount input)]
    (cond-> account
            (= :sub-ledger-kind-unknown (:sub-ledger-kind account))
            (dissoc :sub-ledger-kind))))

(defn LedgerAccount->pb
  "Serialise a LedgerAccount map to protobuf bytes.

  Args:
  - m: LedgerAccount map matching the generated schema."
  [m]
  (proto/->pb (ledger-accounts/new-LedgerAccount m)))

(defn LedgerAccount->java
  "Parse a LedgerAccount map into the generated Java protobuf class.

  Args:
  - m: LedgerAccount map matching the generated schema."
  [m]
  (LedgerAccountProto$LedgerAccount/parseFrom (LedgerAccount->pb m)))

(defn pb->InboundPayment
  "Parse InboundPayment protobuf bytes into a Clojure map, stripping
  every optional string that deserialises as the proto2 empty-string
  default so each key is present only when the record carries a real
  value: a suspended inbound credits no account, a held or returned one
  posts no transaction, the scheme supplies `debtor-name` and
  `reference` only when the debtor's bank sent them, and only a suspended
  or returned one carries a `suspense-reason-code` and `suspense-reason`,
  and only a suspended one the provider did not send back a
  `return-failure-reason`.

  Args:
  - input: protobuf bytes."
  [input]
  (let [payment (payments/pb->InboundPayment input)]
    (cond->
     payment
     (= "" (:creditor-account-id payment))
     (dissoc :creditor-account-id)

     (= "" (:transaction-id payment))
     (dissoc :transaction-id)

     (= "" (:debtor-name payment))
     (dissoc :debtor-name)

     (= "" (:reference payment))
     (dissoc :reference)

     (= "" (:suspense-reason-code payment))
     (dissoc :suspense-reason-code)

     (= "" (:suspense-reason payment))
     (dissoc :suspense-reason)

     (= "" (:return-failure-reason payment))
     (dissoc :return-failure-reason))))

(defn InboundPayment->pb
  "Serialise an InboundPayment map to protobuf bytes.

  Args:
  - m: InboundPayment map matching the generated schema."
  [m]
  (proto/->pb (payments/new-InboundPayment m)))

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
  payments/InboundPaymentStatus-label2val)

(defn pb->OutboundPayment
  "Parse OutboundPayment protobuf bytes into a Clojure map, stripping
  every optional field that deserialises as its proto2 default so each
  key is present only when the record carries a real value: a payment
  the caller sent no `reference` for carries none, and only a failed one
  carries a `failure-kind`, `failure-reason-code` and `failure-reason`,
  and only a returned one a `return-reason-code` and `return-reason`.
  Drops `cancellation-code` and `cancellation-reason`, which are
  deprecated.

  Args:
  - input: protobuf bytes."
  [input]
  (let [payment (dissoc (payments/pb->OutboundPayment input)
                 :cancellation-code
                 :cancellation-reason)]
    (cond-> payment
            (= "" (:reference payment))
            (dissoc :reference)

            (= :outbound-payment-failure-kind-unknown (:failure-kind payment))
            (dissoc :failure-kind)

            (= "" (:failure-reason-code payment))
            (dissoc :failure-reason-code)

            (= "" (:failure-reason payment))
            (dissoc :failure-reason)

            (= "" (:return-reason-code payment))
            (dissoc :return-reason-code)

            (= "" (:return-reason payment))
            (dissoc :return-reason))))

(defn OutboundPayment->pb
  "Serialise an OutboundPayment map to protobuf bytes.

  Args:
  - m: OutboundPayment map matching the generated schema."
  [m]
  (proto/->pb (payments/new-OutboundPayment m)))

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
  payments/OutboundPaymentStatus-label2val)

(defn pb->InternalPayment
  "Parse InternalPayment protobuf bytes into a Clojure map, stripping
  the optional `reference` when it deserialises as the proto2
  empty-string default, so the key is present only on a transfer the
  caller gave one.

  Args:
  - input: protobuf bytes."
  [input]
  (let [payment (payments/pb->InternalPayment input)]
    (cond-> payment
            (= "" (:reference payment))
            (dissoc :reference))))

(defn InternalPayment->pb
  "Serialise an InternalPayment map to protobuf bytes.

  Args:
  - m: InternalPayment map matching the generated schema."
  [m]
  (proto/->pb (payments/new-InternalPayment m)))

(defn InternalPayment->java
  "Parse an InternalPayment map into the generated Java protobuf
  class.

  Args:
  - m: InternalPayment map matching the generated schema."
  [m]
  (InternalPaymentProto$InternalPayment/parseFrom
   (InternalPayment->pb m)))

(defn pb->Transaction
  "Parse Transaction protobuf bytes into a Clojure map. Strips
  `bank-id` when it deserialises as the proto2 empty-string default
  — records written before the idempotency-key index was scoped by
  bank carry no bank."
  [input]
  (let [transaction (transactions/pb->Transaction input)]
    (cond-> transaction
            (= "" (:bank-id transaction))
            (dissoc :bank-id))))

(defn Transaction->pb
  "Serialise a Transaction map to protobuf bytes.

  Args:
  - m: Transaction map matching the generated schema."
  [m]
  (proto/->pb (transactions/new-Transaction m)))

(defn Transaction->java
  "Parse a Transaction map into the generated Java protobuf class.

  Args:
  - m: Transaction map matching the generated schema."
  [m]
  (TransactionProto$Transaction/parseFrom (Transaction->pb m)))

(def ^{:doc "Parse TransactionLeg protobuf bytes into a Clojure
  map."}
     pb->TransactionLeg
  transactions/pb->TransactionLeg)

(defn TransactionLeg->pb
  "Serialise a TransactionLeg map to protobuf bytes.

  Args:
  - m: TransactionLeg map matching the generated schema."
  [m]
  (proto/->pb (transactions/new-TransactionLeg m)))

(defn TransactionLeg->java
  "Parse a TransactionLeg map into the generated Java protobuf
  class.

  Args:
  - m: TransactionLeg map matching the generated schema."
  [m]
  (TransactionProto$TransactionLeg/parseFrom (TransactionLeg->pb m)))

(def ^{:doc "Parse PayeeCheck protobuf bytes into a Clojure map."}
     pb->PayeeCheck
  payee-check/pb->PayeeCheck)

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

(def ^{:doc "Map of ProviderTransferStatus keyword to its proto enum int."}
     provider-transfer-status->int
  payments/ProviderTransferStatus-label2val)

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

(defn pb->ProviderTransfer
  "Parse ProviderTransfer protobuf bytes into a Clojure map, stripping
  the optional `debtor-account-id` and `failure-reason` when they
  deserialise as the proto2 empty-string default.

  Args:
  - input: protobuf bytes."
  [input]
  (let [transfer (payments/pb->ProviderTransfer input)]
    (cond-> transfer
            (= "" (:debtor-account-id transfer))
            (dissoc :debtor-account-id)

            (= "" (:failure-reason transfer))
            (dissoc :failure-reason))))

(defn ProviderTransfer->pb
  "Serialise a ProviderTransfer map to protobuf bytes.

  Args:
  - m: ProviderTransfer map matching the generated schema."
  [m]
  (proto/->pb (payments/new-ProviderTransfer m)))

(defn ProviderTransfer->java
  "Parse a ProviderTransfer map into the generated Java protobuf class.

  Args:
  - m: ProviderTransfer map matching the generated schema."
  [m]
  (ProviderTransferProto$ProviderTransfer/parseFrom (ProviderTransfer->pb m)))

(def ^{:doc "Parse Policy protobuf bytes into a Clojure map."} pb->Policy
  policies/pb->Policy)

(defn Policy->pb
  "Serialise a Policy map to protobuf bytes.

  Args:
  - m: Policy map matching the generated schema."
  [m]
  (proto/->pb (policies/new-Policy m)))

(defn Policy->java
  "Parse a Policy map into the generated Java protobuf class.

  Args:
  - m: Policy map matching the generated schema."
  [m]
  (PolicyProto$Policy/parseFrom (Policy->pb m)))

(def ^{:doc "Parse PolicyBinding protobuf bytes into a Clojure
  map."}
     pb->PolicyBinding
  policies/pb->PolicyBinding)

(defn PolicyBinding->pb
  "Serialise a PolicyBinding map to protobuf bytes.

  Args:
  - m: PolicyBinding map matching the generated schema."
  [m]
  (proto/->pb (policies/new-PolicyBinding m)))

(defn PolicyBinding->java
  "Parse a PolicyBinding map into the generated Java protobuf
  class.

  Args:
  - m: PolicyBinding map matching the generated schema."
  [m]
  (PolicyProto$PolicyBinding/parseFrom (PolicyBinding->pb m)))

(def ^{:doc "Parse User protobuf bytes into a Clojure map."} pb->User
  users/pb->User)

(defn User->pb
  "Serialise a User map to protobuf bytes.

  Args:
  - m: User map matching the generated schema."
  [m]
  (proto/->pb (users/new-User m)))

(defn User->java
  "Parse a User map into the generated Java protobuf class.

  Args:
  - m: User map matching the generated schema."
  [m]
  (UserProto$User/parseFrom (User->pb m)))

(def ^{:doc "Map of IdentityProvider label to protobuf int value."}
     identity-provider->int
  users/IdentityProvider-label2val)

(defn identity-provider->pb-enum
  "Convert an identity-provider keyword to the protobuf enum value,
  for use in FDB index queries.

  Args:
  - identity-provider: `:identity-provider-*` keyword."
  [identity-provider]
  (UserProto$IdentityProvider/forNumber
   (identity-provider->int identity-provider)))

(def ^{:doc "Map of UserStatus label to protobuf int value."} user-status->int
  users/UserStatus-label2val)

(defn user-status->pb-enum
  "Convert a user-status keyword to the protobuf enum value, for
  use in FDB index queries.

  Args:
  - user-status: `:user-status-*` keyword."
  [user-status]
  (UserProto$UserStatus/forNumber
   (user-status->int user-status)))

(def ^:private membership-unset {:ended-at 0 :ended-by nil :invitation-id ""})

(defn pb->Membership
  "Parse Membership protobuf bytes into a Clojure map. A row with no
  status, or `:membership-status-unknown`, reads as
  `:membership-status-active`: every membership written before one could
  end is active. `ended-at`, `ended-by` and `invitation-id` are present
  only when set, and `ended-by` is a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (memberships/pb->Membership input)
      (without-unset membership-unset)
      (plain-embedded :ended-by)
      (update :status
              #(if (= :membership-status-unknown %)
                 :membership-status-active
                 %))))

(defn Membership->pb
  "Serialise a Membership map to protobuf bytes.

  Args:
  - m: Membership map matching the generated schema."
  [m]
  (proto/->pb (memberships/new-Membership m)))

(defn Membership->java
  "Parse a Membership map into the generated Java protobuf class.

  Args:
  - m: Membership map matching the generated schema."
  [m]
  (MembershipProto$Membership/parseFrom (Membership->pb m)))

(def ^{:doc "Map of Membership Role label to protobuf int value."} role->int
  memberships/Role-label2val)

(defn role->pb-enum
  "Convert a membership role keyword to the protobuf enum value,
  for use in FDB index queries.

  Args:
  - role: `:role-*` keyword."
  [role]
  (MembershipProto$Role/forNumber (role->int role)))

(def ^{:doc "Map of MembershipStatus label to protobuf int value."}
     membership-status->int
  memberships/MembershipStatus-label2val)

(defn membership-status->pb-enum
  "Convert a membership-status keyword to the protobuf enum value, for
  use in FDB index queries.

  Args:
  - membership-status: `:membership-status-*` keyword."
  [membership-status]
  (MembershipProto$MembershipStatus/forNumber
   (membership-status->int membership-status)))

(def ^{:doc "Map of ActorKind label to protobuf int value."} actor-kind->int
  memberships/ActorKind-label2val)

(defn actor-kind->pb-enum
  "Convert an actor-kind keyword to the protobuf enum value, for use in
  FDB index queries.

  Args:
  - actor-kind: `:actor-kind-*` keyword."
  [actor-kind]
  (ActorProto$ActorKind/forNumber (actor-kind->int actor-kind)))

(def ^:private invitation-unset {:accepted-by-user-id "" :reason ""})

(defn pb->Invitation
  "Parse Invitation protobuf bytes into a Clojure map. `reason` and
  `accepted-by-user-id` are present only when set, and `invited-by` is a
  plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (memberships/pb->Invitation input)
      (without-unset invitation-unset)
      (plain-embedded :invited-by)))

(defn Invitation->pb
  "Serialise an Invitation map to protobuf bytes.

  Args:
  - m: Invitation map matching the generated schema."
  [m]
  (proto/->pb (memberships/new-Invitation m)))

(defn Invitation->java
  "Parse an Invitation map into the generated Java protobuf class.

  Args:
  - m: Invitation map matching the generated schema."
  [m]
  (InvitationProto$Invitation/parseFrom (Invitation->pb m)))

(def ^{:doc "Map of InvitationStatus label to protobuf int value."}
     invitation-status->int
  memberships/InvitationStatus-label2val)

(defn invitation-status->pb-enum
  "Convert an invitation-status keyword to the protobuf enum value, for
  use in FDB index queries.

  Args:
  - invitation-status: `:invitation-status-*` keyword."
  [invitation-status]
  (InvitationProto$InvitationStatus/forNumber
   (invitation-status->int invitation-status)))

(def ^:private access-event-unset
  {:email ""
   :invitation-id ""
   :membership-id ""
   :reason ""
   :role-after :role-unknown
   :role-before :role-unknown
   :subject-user-id ""})

(defn pb->AccessEvent
  "Parse AccessEvent protobuf bytes into a Clojure map. Of the fields a
  kind sets only where it has one — `subject-user-id`, `membership-id`,
  `invitation-id`, `email`, `role-before`, `role-after` and `reason` —
  each is present only when set, and `actor` is a plain map.

  Args:
  - input: protobuf bytes."
  [input]
  (-> (memberships/pb->AccessEvent input)
      (without-unset access-event-unset)
      (plain-embedded :actor)))

(defn AccessEvent->pb
  "Serialise an AccessEvent map to protobuf bytes. `:kind` is required:
  the generated default for an absent one is not
  `:access-event-kind-unknown`.

  Args:
  - m: AccessEvent map matching the generated schema."
  [m]
  (proto/->pb (memberships/new-AccessEvent m)))

(defn AccessEvent->java
  "Parse an AccessEvent map into the generated Java protobuf class.

  Args:
  - m: AccessEvent map matching the generated schema."
  [m]
  (AccessEventProto$AccessEvent/parseFrom (AccessEvent->pb m)))

(def ^{:doc "Map of AccessEventKind label to protobuf int value."}
     access-event-kind->int
  memberships/AccessEventKind-label2val)

(defn access-event-kind->pb-enum
  "Convert an access-event-kind keyword to the protobuf enum value, for
  use in FDB index queries.

  Args:
  - access-event-kind: `:access-event-kind-*` keyword."
  [access-event-kind]
  (AccessEventProto$AccessEventKind/forNumber
   (access-event-kind->int access-event-kind)))

(defn pb->WebhookEndpoint
  "Parse WebhookEndpoint protobuf bytes into a Clojure map. Drops
  `last-success-at`, which is deprecated.

  Args:
  - input: protobuf bytes."
  [input]
  (dissoc (webhooks/pb->WebhookEndpoint input) :last-success-at))

(defn WebhookEndpoint->pb
  "Serialise a WebhookEndpoint map to protobuf bytes.

  Args:
  - m: WebhookEndpoint map matching the generated schema."
  [m]
  (proto/->pb (webhooks/new-WebhookEndpoint m)))

(defn WebhookEndpoint->java
  "Parse a WebhookEndpoint map into the generated Java protobuf class.

  Args:
  - m: WebhookEndpoint map matching the generated schema."
  [m]
  (WebhookEndpointProto$WebhookEndpoint/parseFrom (WebhookEndpoint->pb m)))

(def ^{:doc "Parse WebhookNotification protobuf bytes into a Clojure map."}
     pb->WebhookNotification
  webhooks/pb->WebhookNotification)

(defn WebhookNotification->pb
  "Serialise a WebhookNotification map to protobuf bytes.

  Args:
  - m: WebhookNotification map matching the generated schema."
  [m]
  (proto/->pb (webhooks/new-WebhookNotification m)))

(defn WebhookNotification->java
  "Parse a WebhookNotification map into the generated Java protobuf class.

  Args:
  - m: WebhookNotification map matching the generated schema."
  [m]
  (WebhookNotificationProto$WebhookNotification/parseFrom
   (WebhookNotification->pb m)))

(def ^{:doc "Parse WebhookDelivery protobuf bytes into a Clojure map."}
     pb->WebhookDelivery
  webhooks/pb->WebhookDelivery)

(defn WebhookDelivery->pb
  "Serialise a WebhookDelivery map to protobuf bytes.

  Args:
  - m: WebhookDelivery map matching the generated schema."
  [m]
  (proto/->pb (webhooks/new-WebhookDelivery m)))

(defn WebhookDelivery->java
  "Parse a WebhookDelivery map into the generated Java protobuf class.

  Args:
  - m: WebhookDelivery map matching the generated schema."
  [m]
  (WebhookDeliveryProto$WebhookDelivery/parseFrom (WebhookDelivery->pb m)))

(def ^{:doc "Parse WebhookDeliveryAttempt protobuf bytes into a Clojure map."}
     pb->WebhookDeliveryAttempt
  webhooks/pb->WebhookDeliveryAttempt)

(defn WebhookDeliveryAttempt->pb
  "Serialise a WebhookDeliveryAttempt map to protobuf bytes.

  Args:
  - m: WebhookDeliveryAttempt map matching the generated schema."
  [m]
  (proto/->pb (webhooks/new-WebhookDeliveryAttempt m)))

(defn WebhookDeliveryAttempt->java
  "Parse a WebhookDeliveryAttempt map into the generated Java protobuf class.

  Args:
  - m: WebhookDeliveryAttempt map matching the generated schema."
  [m]
  (WebhookDeliveryAttemptProto$WebhookDeliveryAttempt/parseFrom
   (WebhookDeliveryAttempt->pb m)))

(def ^{:doc "Map of WebhookEndpointStatus label to protobuf int value."}
     webhook-endpoint-status->int
  webhooks/WebhookEndpointStatus-label2val)

(def ^{:doc "Map of WebhookDeliveryStatus label to protobuf int value."}
     webhook-delivery-status->int
  webhooks/WebhookDeliveryStatus-label2val)

(def ^:private email-delivery-unset
  {:message-id ""
   :sent-at 0
   :next-attempt-at 0
   :traceparent ""
   :failure-reason ""})

(defn pb->EmailDelivery
  "Parse EmailDelivery protobuf bytes into a Clojure map. Each optional
  field is present only when set.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (emails/pb->EmailDelivery input) email-delivery-unset))

(defn EmailDelivery->pb
  "Serialise an EmailDelivery map to protobuf bytes.

  Args:
  - m: EmailDelivery map matching the generated schema."
  [m]
  (proto/->pb (emails/new-EmailDelivery m)))

(defn EmailDelivery->java
  "Parse an EmailDelivery map into the generated Java protobuf class.

  Args:
  - m: EmailDelivery map matching the generated schema."
  [m]
  (EmailDeliveryProto$EmailDelivery/parseFrom (EmailDelivery->pb m)))

(def ^{:doc "Map of EmailDeliveryStatus label to protobuf int value."}
     email-delivery-status->int
  emails/EmailDeliveryStatus-label2val)

(def ^{:doc "Map of EmailKind label to protobuf int value."} email-kind->int
  emails/EmailKind-label2val)

(def ^:private circuit-breaker-unset
  {:consecutive-failures 0
   :opened-at 0
   :retry-at 0
   :cool-down-ms 0
   :probe-claimed-by ""
   :probe-lease-expires-at 0
   :updated-at 0})

(defn pb->CircuitBreaker
  "Parse CircuitBreaker protobuf bytes into a Clojure map. Each optional
  field is present only when set.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (outbound/pb->CircuitBreaker input) circuit-breaker-unset))

(defn CircuitBreaker->pb
  "Serialise a CircuitBreaker map to protobuf bytes.

  Args:
  - m: CircuitBreaker map matching the generated schema."
  [m]
  (proto/->pb (outbound/new-CircuitBreaker m)))

(defn CircuitBreaker->java
  "Parse a CircuitBreaker map into the generated Java protobuf class.

  Args:
  - m: CircuitBreaker map matching the generated schema."
  [m]
  (CircuitBreakerProto$CircuitBreaker/parseFrom (CircuitBreaker->pb m)))

(def ^:private reward-unset
  {:transaction-id "" :run-id "" :error "" :paid-at 0})

(defn pb->Reward
  "Parse Reward protobuf bytes into a Clojure map. `transaction-id`,
  `run-id`, `error` and `paid-at` are present only when set.

  Args:
  - input: protobuf bytes."
  [input]
  (without-unset (rewards/pb->Reward input) reward-unset))

(defn Reward->pb
  "Serialise a Reward map to protobuf bytes.

  Args:
  - m: Reward map matching the generated schema."
  [m]
  (proto/->pb (rewards/new-Reward m)))

(defn Reward->java
  "Parse a Reward map into the generated Java protobuf class.

  Args:
  - m: Reward map matching the generated schema."
  [m]
  (RewardProto$Reward/parseFrom (Reward->pb m)))

(def ^{:doc "Map of RewardKind label to protobuf int value."} reward-kind->int
  cash-account-products/RewardKind-label2val)

(def ^{:doc "Map of RewardStatus label to protobuf int value."}
     reward-status->int
  rewards/RewardStatus-label2val)
