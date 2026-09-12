(ns com.repldriven.queenswood.api.cash-account-migration.components
  (:require
    [com.repldriven.queenswood.api.cash-account-migration.coercion :as coercion]
    [com.repldriven.queenswood.api.cash-account-migration.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :as schema :refer
     [components-registry]]))

(def MigrationId (schema/id-schema "MigrationId" "mig" examples/MigrationId))

(def MigrationRunId
  [:re
   {:title "MigrationRunId"
    :json-schema/example examples/RunId
    :description "Time-ordered run identifier (uuidv7)."}
   #"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"])

(def MigrationStatus
  (coercion/status-enum-schema {:json-schema/example "draft"}))

(def MigrationRunStatus
  (coercion/run-status-enum-schema {:json-schema/example "completed"}))

(def MigrationOutcome
  (coercion/outcome-enum-schema {:json-schema/example "ineligible"}))

(def MigrationIneligibility
  (coercion/ineligibility-enum-schema {:json-schema/example
                                       "currency-not-allowed"}))

(def Migration
  "A planned move of a product's cash accounts onto a different product
  version. Authoring one moves nothing — accounts are only ever moved by
  the scheduler."
  [:map {:json-schema/example examples/Migration}
   [:bank-id [:ref "BankId"]]
   [:migration-id [:ref "MigrationId"]]
   [:status [:ref "MigrationStatus"]]
   [:name [:ref "Name"]]
   [:source-product-id [:ref "ProductId"]]
   ;; Absent when the migration takes every version of its source.
   [:source-version-ids {:optional true} [:vector [:ref "VersionId"]]]
   [:target-product-id [:ref "ProductId"]]
   [:target-version-id [:ref "VersionId"]]
   [:notified-on {:optional true} [:ref "BusinessDay"]]
   [:due-on {:optional true} [:ref "BusinessDay"]]
   [:created-at [:ref "Timestamp"]]
   [:updated-at [:ref "Timestamp"]]
   [:approved-at {:optional true} [:ref "Timestamp"]]
   [:completed-at {:optional true} [:ref "Timestamp"]]
   [:cancelled-at {:optional true} [:ref "Timestamp"]]])

(def MigrationList
  [:map {:json-schema/example examples/MigrationList}
   [:migrations [:vector [:ref "Migration"]]]])

(def MigrationCreate
  "Naming a target version rather than a product is deliberate: approval
  means these accounts move to these terms, and a floating target would
  let a version published afterwards change what was agreed."
  [:map {:closed true :json-schema/example examples/MigrationCreate}
   [:name [:ref "Name"]]
   [:source-product-id [:ref "ProductId"]]
   ;; Narrows the cohort to accounts on these versions. Every version of
   ;; the source product when omitted.
   [:source-version-ids {:optional true} [:vector [:ref "VersionId"]]]
   [:target-product-id [:ref "ProductId"]]
   [:target-version-id [:ref "VersionId"]]
   [:notified-on {:optional true} [:ref "BusinessDay"]]
   [:due-on {:optional true} [:ref "BusinessDay"]]])

(def MigrationRun
  "One evaluation of a migration. `dry-run` true is a preview — it
  decided about every account and moved none."
  [:map {:json-schema/example examples/MigrationRun}
   [:bank-id [:ref "BankId"]]
   [:run-id [:ref "MigrationRunId"]]
   [:migration-id [:ref "MigrationId"]]
   [:status [:ref "MigrationRunStatus"]]
   [:dry-run boolean?]
   [:business-day [:ref "BusinessDay"]]
   [:started-at [:ref "Timestamp"]]
   [:finished-at {:optional true} [:ref "Timestamp"]]
   [:error {:optional true} string?]
   [:accounts-seen {:optional true} nat-int?]
   [:accounts-moved {:optional true} nat-int?]
   [:accounts-ineligible {:optional true} nat-int?]
   [:accounts-failed {:optional true} nat-int?]])

(def MigrationRunList
  [:map {:json-schema/example examples/MigrationRunList}
   [:runs [:vector [:ref "MigrationRun"]]]])

(def MigrationAccountRun
  "What a run decided about one account. An ineligible account carries
  the reason — the counts per reason are what a preview is read for."
  [:map {:json-schema/example examples/MigrationAccountRun}
   [:bank-id [:ref "BankId"]]
   [:run-id [:ref "MigrationRunId"]]
   [:migration-id [:ref "MigrationId"]]
   [:account-id [:ref "CashAccountId"]]
   [:outcome [:ref "MigrationOutcome"]]
   [:from-version-id {:optional true} [:ref "VersionId"]]
   ;; Set only where an account actually moved.
   [:to-version-id {:optional true} [:ref "VersionId"]]
   [:ineligibility {:optional true} [:ref "MigrationIneligibility"]]
   [:failure-reason {:optional true} string?]
   [:created-at [:ref "Timestamp"]]])

(def MigrationAccountRunList
  [:map {:json-schema/example examples/MigrationAccountRunList}
   [:accounts [:vector [:ref "MigrationAccountRun"]]]])

(def registry
  (components-registry [#'MigrationId #'MigrationRunId #'MigrationStatus
                        #'MigrationRunStatus #'MigrationOutcome
                        #'MigrationIneligibility #'Migration #'MigrationList
                        #'MigrationCreate #'MigrationRun #'MigrationRunList
                        #'MigrationAccountRun #'MigrationAccountRunList]))
