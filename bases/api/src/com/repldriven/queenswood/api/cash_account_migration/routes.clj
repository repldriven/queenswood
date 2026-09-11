(ns com.repldriven.queenswood.api.cash-account-migration.routes
  (:require
    [com.repldriven.queenswood.api.cash-account-migration.examples :refer
     [MigrationNotFound RunNotFound ProductTypeMismatch TargetNotPublished
      TargetIsSource NoticeAfterDue NameRequired SourceProductNotFound
      InvalidStatus NoticeRequired]]
    [com.repldriven.queenswood.api.cash-account-migration.handlers :as handlers]
    [com.repldriven.queenswood.api.cash-account-migration.queries :as queries]

    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

(def ^:private migration-location-header
  {:schema {:type "string"} :description "URI of the new migration"})

(def ^:private preview-location-header
  {:schema {:type "string"} :description "URI of the new preview"})

;; A migration is its own resource rather than a sub-resource of a
;; product: it names two of them, and neither owns it.
;;
;; Note what is absent. There is no POST on a migration's runs — the
;; only thing that moves accounts is the scheduler's migration task. The
;; API authors a migration, previews it, approves or cancels it, and
;; reads outcomes; approving decides that accounts will move, never that
;; they move now. That the surface cannot express a commit is the point,
;; not an omission.
(def routes
  [["/cash-account-migrations"
    {:openapi {:tags ["Cash-account migrations"]
               :security [{"bearerAuth" ["org"]}]}}
    [""
     {:get {:summary "Retrieve cash-account migrations"
            :openapi {:operationId "RetrieveCashAccountMigrations"}
            :responses {200 {:body [:ref "MigrationList"]}}
            :handler queries/list-migrations}
      :post {:summary "Author a cash-account migration"
             :openapi {:operationId "CreateCashAccountMigration"
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "MigrationCreate"]}
             :responses (shared.idempotency/with-responses
                         {201 {:body [:ref "Migration"]
                               :openapi {:headers {"Location"
                                                   migration-location-header}}}
                          404 (ErrorResponse [#'SourceProductNotFound])
                          422 (ErrorResponse [#'ProductTypeMismatch
                                              #'TargetNotPublished
                                              #'TargetIsSource
                                              #'NoticeAfterDue
                                              #'NameRequired])})
             :handler handlers/create-migration}}]
    ["/{migration-id}"
     {:parameters {:path {:migration-id [:ref "MigrationId"]}}}
     [""
      {:get {:summary "Retrieve a cash-account migration"
             :openapi {:operationId "RetrieveCashAccountMigration"}
             :responses {200 {:body [:ref "Migration"]}
                         404 (ErrorResponse [#'MigrationNotFound])}
             :handler queries/get-migration}}]
     ["/approve"
      {:post {:summary "Approve a cash-account migration"
              :openapi {:operationId "ApproveCashAccountMigration"}
              :responses {200 {:body [:ref "Migration"]}
                          404 (ErrorResponse [#'MigrationNotFound])
                          409 (ErrorResponse [#'InvalidStatus])
                          422 (ErrorResponse [#'NoticeRequired])}
              :handler handlers/approve-migration}}]
     ["/cancel"
      {:post {:summary "Cancel a cash-account migration"
              :openapi {:operationId "CancelCashAccountMigration"}
              :responses {200 {:body [:ref "Migration"]}
                          404 (ErrorResponse [#'MigrationNotFound])
                          409 (ErrorResponse [#'InvalidStatus])}
              :handler handlers/cancel-migration}}]
     ["/previews"
      [""
       {:get {:summary "Retrieve a migration's previews"
              :openapi {:operationId "RetrieveCashAccountMigrationPreviews"}
              :responses {200 {:body [:ref "MigrationRunList"]}
                          404 (ErrorResponse [#'MigrationNotFound])}
              :handler queries/list-runs}
        :post {:summary "Preview a migration without moving accounts"
               :openapi {:operationId "PreviewCashAccountMigration"
                         :parameters ^:replace
                                     [shared.parameters/ref-migration-id
                                      shared.parameters/ref-idempotency-key]}
               :interceptors [server/require-idempotency-key
                              bank-idempotency/cache-response]
               :responses (shared.idempotency/with-responses
                           {201 {:body [:ref "MigrationRun"]
                                 :openapi {:headers {"Location"
                                                     preview-location-header}}}
                            404 (ErrorResponse [#'MigrationNotFound])})
               :handler handlers/preview-migration}}]
      ["/{run-id}"
       {:parameters {:path {:run-id [:ref "MigrationRunId"]}}}
       [""
        {:get {:summary "Retrieve a migration preview"
               :openapi {:operationId "RetrieveCashAccountMigrationPreview"}
               :responses {200 {:body [:ref "MigrationRun"]}
                           404 (ErrorResponse [#'RunNotFound])}
               :handler queries/get-run}}]
       ["/accounts"
        {:get {:summary "Retrieve a preview's per-account verdicts"
               :openapi {:operationId
                         "RetrieveCashAccountMigrationPreviewAccounts"}
               :responses {200 {:body [:ref "MigrationAccountRunList"]}
                           404 (ErrorResponse [#'RunNotFound])}
               :handler queries/list-run-accounts}}]]]]]])
