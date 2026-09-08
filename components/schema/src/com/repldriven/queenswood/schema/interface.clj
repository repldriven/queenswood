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
    [com.repldriven.queenswood.schemas.idempotency :as idempotency]
    [com.repldriven.queenswood.schemas.idv :as idv]
    [com.repldriven.queenswood.schemas.interest :as interest]
    [com.repldriven.queenswood.schemas.ledger_accounts :as ledger-accounts]
    [com.repldriven.queenswood.schemas.memberships :as memberships]
    [com.repldriven.queenswood.schemas.onfido :as onfido]
    [com.repldriven.queenswood.schemas.party :as party]
    [com.repldriven.queenswood.schemas.payee_check :as payee-check]
    [com.repldriven.queenswood.schemas.payments :as payments]
    [com.repldriven.queenswood.schemas.person_identification :as
     person-identification]
    [com.repldriven.queenswood.schemas.policies :as policies]
    [com.repldriven.queenswood.schemas.scheduler :as scheduler]
    [com.repldriven.queenswood.schemas.transactions :as transactions]
    [com.repldriven.queenswood.schemas.types :as types]
    [com.repldriven.queenswood.schemas.users :as users]

    [protojure.protobuf :as proto])
  (:import
    (com.repldriven.queenswood.schemas.balances BalanceProto$Balance)
    (com.repldriven.queenswood.schemas.cash_account_migrations
     CashAccountMigrationProto$CashAccountMigration
     CashAccountMigrationRunProto$CashAccountMigrationRun
     CashAccountMigrationRunProto$CashAccountMigrationAccountRun)
    (com.repldriven.queenswood.schemas.cash_account_products
     CashAccountProductProto$CashAccountProduct
     CashAccountProductProto$CashAccountProductTemplate
     CashAccountProductProto$IsoCashAccountType)
    (com.repldriven.queenswood.schemas.cash_accounts
     CashAccountProto$CashAccount)
    (com.repldriven.queenswood.schemas.company CompanyProto$Company)
    (com.repldriven.queenswood.schemas.idempotency IdempotencyProto$Idempotency)
    (com.repldriven.queenswood.schemas.idv IdvProto$Idv)
    (com.repldriven.queenswood.schemas.interest
     InterestRunProto$InterestRun
     InterestRunProto$InterestAccountRun)
    (com.repldriven.queenswood.schemas.ledger_accounts
     LedgerAccountProto$LedgerAccount
     LedgerAccountProto$GlAccountCode)
    (com.repldriven.queenswood.schemas.scheduler
     SchedulerJobProto$SchedulerJob
     SchedulerRunProto$SchedulerRun)
    (com.repldriven.queenswood.schemas.banks BankProto$Bank)
    (com.repldriven.queenswood.schemas.party
     PartyProto$Party
     PartyNationalIdentifierProto$PartyNationalIdentifier)
    (com.repldriven.queenswood.schemas.person_identification
     PersonIdentificationProto$PersonIdentification)
    (com.repldriven.queenswood.schemas.payments
     InboundPaymentProto$InboundPayment
     InternalPaymentProto$InternalPayment
     OutboundPaymentProto$OutboundPayment)
    (com.repldriven.queenswood.schemas.payee_check
     PayeeCheckProto$PayeeCheck)
    (com.repldriven.queenswood.schemas.clearbank
     ClearbankOutboxProto$ClearbankOutboxEvent
     ClearbankOutboxProto$ClearbankOutboundIntent)
    (com.repldriven.queenswood.schemas.onfido
     OnfidoOutboxProto$OnfidoOutboxEvent
     OnfidoOutboxProto$OnfidoOutboundIntent)
    (com.repldriven.queenswood.schemas.policies
     PolicyProto$Policy
     PolicyProto$PolicyBinding)
    (com.repldriven.queenswood.schemas.transactions
     TransactionProto$Transaction
     TransactionProto$TransactionLeg
     TransactionProto$TransactionType)
    (com.repldriven.queenswood.schemas.users
     UserProto$User
     UserProto$IdentityProvider
     UserProto$UserStatus)
    (com.repldriven.queenswood.schemas.memberships
     MembershipProto$Membership
     MembershipProto$Role)))

(def ^{:doc "Parse Balance protobuf bytes into a Clojure map."} pb->Balance
  balances/pb->Balance)

(defn Balance->pb
  "Serialise a Balance map to protobuf bytes.

  Args:
  - m: Balance map matching the generated schema."
  [m]
  (proto/->pb (balances/new-Balance m)))

(defn Balance->java
  "Parse a Balance map into the generated Java protobuf class.

  Args:
  - m: Balance map matching the generated schema."
  [m]
  (BalanceProto$Balance/parseFrom (Balance->pb m)))

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

(defn iso-cash-account-type->pb-enum
  "Convert an iso-cash-account-type keyword to the protobuf enum
  value, for use in FDB index queries.

  Args:
  - iso-cash-account-type: `:iso-cash-account-type-*` keyword."
  [iso-cash-account-type]
  (CashAccountProductProto$IsoCashAccountType/forNumber
   (iso-cash-account-type->int iso-cash-account-type)))

(def transaction-type->int transactions/TransactionType-label2val)

(defn transaction-type->pb-enum
  "Convert a transaction-type keyword to the protobuf enum value, for
  use as the comparand in an FDB enum-field index query."
  [transaction-type]
  (TransactionProto$TransactionType/forNumber
   (transaction-type->int transaction-type)))


(defn pb->CashAccountProduct
  "Parse CashAccountProduct protobuf bytes into a Clojure map, dropping
  the `0` default proto2 emits for an unset optional `effective_from` /
  `effective_to` so callers see those keys only when a real epoch-day
  is set (epoch-day 0 is 1970-01-01, never a real product window), and
  the `false` default for `internal` so the flag is present only on
  internal products (which never reach a customer response), and the
  empty-string default for `idempotency_key` so only new-product
  versions carry one (others never took a unique-index entry).

  Args:
  - input: protobuf bytes."
  [input]
  (let [version (cash-account-products/pb->CashAccountProduct input)]
    (cond-> version
            (zero? (:effective-from version 0))
            (dissoc :effective-from)

            (zero? (:effective-to version 0))
            (dissoc :effective-to)

            (not (:internal version))
            (dissoc :internal)

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
  (CashAccountProductProto$CashAccountProductTemplate/parseFrom
   (CashAccountProductTemplate->pb m)))

(def ^{:doc "Parse Company protobuf bytes into a Clojure map."} pb->Company
  company/pb->Company)

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

(def ^{:doc "Parse Idempotency protobuf bytes into a Clojure map."}
     pb->Idempotency
  idempotency/pb->Idempotency)

(defn Idempotency->pb
  "Serialise an Idempotency map to protobuf bytes."
  [m]
  (proto/->pb (idempotency/new-Idempotency m)))

(defn Idempotency->java
  "Parse an Idempotency map into the generated Java protobuf class."
  [m]
  (IdempotencyProto$Idempotency/parseFrom (Idempotency->pb m)))

(def ^{:doc "Parse Bank protobuf bytes into a Clojure map."} pb->Bank
  banks/pb->Bank)

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
  `merged-into-party-id` and `idempotency-key` when they deserialise
  as the proto2 empty-string default — every party except a
  merged-away one leaves the first unset, and every party not created
  by a client command leaves the second unset."
  [input]
  (let [party (party/pb->Party input)]
    (cond-> party
            (= "" (:merged-into-party-id party))
            (dissoc :merged-into-party-id)

            (= "" (:idempotency-key party))
            (dissoc :idempotency-key))))

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

(def ^{:doc
       "Parse PartyNationalIdentifier protobuf bytes into a
  Clojure map."}
     pb->PartyNationalIdentifier
  party/pb->PartyNationalIdentifier)

(defn PartyNationalIdentifier->pb
  "Serialise a PartyNationalIdentifier map to protobuf bytes.

  Args:
  - m: PartyNationalIdentifier map matching the generated schema."
  [m]
  (proto/->pb (party/new-PartyNationalIdentifier m)))

(defn PartyNationalIdentifier->java
  "Parse a PartyNationalIdentifier map into the generated Java
  protobuf class.

  Args:
  - m: PartyNationalIdentifier map matching the generated schema."
  [m]
  (PartyNationalIdentifierProto$PartyNationalIdentifier/parseFrom
   (PartyNationalIdentifier->pb m)))

(def ^{:doc "Parse PersonIdentification protobuf bytes into a
  Clojure map."}
     pb->PersonIdentification
  person-identification/pb->PersonIdentification)

(defn PersonIdentification->pb
  "Serialise a PersonIdentification map to protobuf bytes.

  Args:
  - m: PersonIdentification map matching the generated schema."
  [m]
  (proto/->pb (person-identification/new-PersonIdentification m)))

(defn PersonIdentification->java
  "Parse a PersonIdentification map into the generated Java
  protobuf class.

  Args:
  - m: PersonIdentification map matching the generated schema."
  [m]
  (PersonIdentificationProto$PersonIdentification/parseFrom
   (PersonIdentification->pb m)))

(def ^{:doc "Parse Idv protobuf bytes into a Clojure map."} pb->Idv idv/pb->Idv)

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
  (CashAccountMigrationRunProto$CashAccountMigrationAccountRun/parseFrom
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
  interest/InterestRun-InterestRunKind-label2val)

(def ^{:doc "Map of InterestRunKind protobuf int value to label."}
     int->interest-run-kind
  interest/InterestRun-InterestRunKind-val2label)

(def ^{:doc "Map of InterestRunState label to protobuf int value."}
     interest-run-state->int
  interest/InterestRun-InterestRunState-label2val)

(def ^{:doc "Map of InterestAccountRunKind label to protobuf int value."}
     interest-account-run-kind->int
  interest/InterestAccountRun-InterestAccountRunKind-label2val)

(def ^{:doc "Map of InterestAccountRunState label to protobuf int value."}
     interest-account-run-state->int
  interest/InterestAccountRun-InterestAccountRunState-label2val)

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
  (InterestRunProto$InterestAccountRun/parseFrom
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

(defn pb->CashAccount
  "Parse CashAccount protobuf bytes into a Clojure map. Strips
  optional string fields that deserialise as the proto2 empty-string
  default (`bban`, `gl-control-account-id`,
  `last-rotation-idempotency-key`) — GL chart-of-accounts rows leave
  the first two unset and an account that has never been rotated
  leaves the third unset, and downstream read sites use `(when (:bban
  account) ...)` semantics to distinguish customer instruments from
  GL rows."
  [input]
  (let [account (cash-accounts/pb->CashAccount input)]
    (cond-> account
            (= "" (:bban account))
            (dissoc :bban)

            (= "" (:gl-control-account-id account))
            (dissoc :gl-control-account-id)

            (= "" (:last-rotation-idempotency-key account))
            (dissoc :last-rotation-idempotency-key))))

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

(def ^{:doc "Parse InboundPayment protobuf bytes into a Clojure
  map."}
     pb->InboundPayment
  payments/pb->InboundPayment)

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

(def ^{:doc "Parse OutboundPayment protobuf bytes into a Clojure
  map."}
     pb->OutboundPayment
  payments/pb->OutboundPayment)

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

(def ^{:doc "Parse InternalPayment protobuf bytes into a Clojure
  map."}
     pb->InternalPayment
  payments/pb->InternalPayment)

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
  (ClearbankOutboxProto$ClearbankOutboxEvent/parseFrom
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
  (ClearbankOutboxProto$ClearbankOutboundIntent/parseFrom
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
  (OnfidoOutboxProto$OnfidoOutboxEvent/parseFrom (OnfidoOutboxEvent->pb m)))

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
  (OnfidoOutboxProto$OnfidoOutboundIntent/parseFrom
   (OnfidoOutboundIntent->pb m)))

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

(def ^{:doc "Parse Membership protobuf bytes into a Clojure map."}
     pb->Membership
  memberships/pb->Membership)

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
