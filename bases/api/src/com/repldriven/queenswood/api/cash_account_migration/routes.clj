(ns com.repldriven.queenswood.api.cash-account-migration.routes
  (:require
    [com.repldriven.queenswood.api.cash-account-migration.handlers :as handlers]
    [com.repldriven.queenswood.api.cash-account-migration.queries :as queries]

    [com.repldriven.queenswood.api.shared.headers :as shared.headers]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :as api-schema :refer
     [ErrorResponse]]
    [com.repldriven.queenswood.cash-account-migration-api.interface :refer
     [InvalidStatus MigrationNotFound NameRequired NoticeAfterDue NoticeRequired
      ProductTypeMismatch RunNotFound SourceProductNotFound TargetIsSource
      TargetNotPublished]]
    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]))

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
    {:openapi {:tags ["Cash Account Migrations"]}}
    [""
     {:get {:summary "List cash account migrations"
            :openapi {:operationId "ListCashAccountMigrations"
                      :description
                      (str "The bank's migrations in every status, newest "
                           "first, a page at a time.")
                      :security [{"bearerAuth" ["org:viewer"]}]
                      :parameters ^:replace
                                  [shared.parameters/ref-page
                                   shared.parameters/ref-bank-id-header]}
            :parameters {:query shared.parameters/page-query}
            :responses {200 {:description
                             "A page of the bank's migrations, newest first."
                             :body [:ref "MigrationList"]}}
            :handler queries/list-migrations}
      :post {:summary "Create a cash account migration"
             :openapi {:operationId "CreateCashAccountMigration"
                       :description
                       (str "Creates the migration as a draft, and moves no "
                            "account. The target must be a published version "
                            "of a product of the same type as the source, and "
                            "the notice date no later than the due date, or "
                            "the request is refused with 422. A source "
                            "product with no versions is refused with 404.")
                       :security [{"bearerAuth" ["org:developer"]}]
                       :requestBody {:required true}
                       :parameters ^:replace
                                   [shared.parameters/ref-bank-id-header
                                    shared.parameters/ref-idempotency-key]}
             :interceptors [server/require-idempotency-key
                            bank-idempotency/cache-response]
             :parameters {:body [:ref "MigrationCreate"]}
             :responses (shared.idempotency/with-responses
                         {201 {:description "The migration, as a draft."
                               :body [:ref "Migration"]
                               :openapi {:headers {"Location"
                                                   (shared.headers/location
                                                    "migration")}}}
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
      {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :get {:summary "Retrieve a cash account migration"
             :openapi {:operationId "RetrieveCashAccountMigration"
                       :description
                       (str "A migration is a draft until approved, and "
                            "completed once the scheduled migration job has "
                            "moved its accounts.")}
             :responses {200 {:description "The migration."
                              :body [:ref "Migration"]}
                         404 (ErrorResponse [#'MigrationNotFound])}
             :handler queries/get-migration}}]
     ["/approve"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :post {:summary "Approve a cash account migration"
              :openapi
              {:operationId "ApproveCashAccountMigration"
               :description
               (str "Commits to moving the migration's accounts. The "
                    "scheduled migration job moves them once the due date is"
                    " reached and the target version is in force, with a "
                    "`cash-account.migrated` webhook notification for each. A "
                    "migration that is not a draft is refused with 409. One "
                    "without both a notice date and a due date is refused "
                    "with 422.")}
              :responses {200 {:description "The approved migration."
                               :body [:ref "Migration"]}
                          404 (ErrorResponse [#'MigrationNotFound])
                          409 (ErrorResponse [#'InvalidStatus])
                          422 (ErrorResponse [#'NoticeRequired])}
              :handler handlers/approve-migration}}]
     ["/cancel"
      {:openapi {:security [{"bearerAuth" ["org:developer"]}]
                 :parameters [shared.parameters/ref-bank-id-header]}
       :post {:summary "Cancel a cash account migration"
              :openapi {:operationId "CancelCashAccountMigration"
                        :description
                        (str "Stops a draft or approved migration, so the "
                             "scheduled migration job never moves its "
                             "accounts. A completed or cancelled migration "
                             "is refused with 409.")}
              :responses {200 {:description "The cancelled migration."
                               :body [:ref "Migration"]}
                          404 (ErrorResponse [#'MigrationNotFound])
                          409 (ErrorResponse [#'InvalidStatus])}
              :handler handlers/cancel-migration}}]
     ["/previews"
      [""
       {:get {:summary "List a migration's previews"
              :openapi {:operationId "ListCashAccountMigrationPreviews"
                        :description
                        (str
                         "Every run of the migration, newest first, a page "
                         "at a time. Once the migration has completed, this "
                         "includes the run that moved its accounts, with "
                         "`dry-run` false.")
                        :security [{"bearerAuth" ["org:viewer"]}]
                        :parameters ^:replace
                                    [shared.parameters/ref-migration-id
                                     shared.parameters/ref-page
                                     shared.parameters/ref-bank-id-header]}
              :parameters {:query shared.parameters/page-query}
              :responses {200 {:description
                               "A page of the migration's runs, newest first."
                               :body [:ref "MigrationRunList"]}
                          404 (ErrorResponse [#'MigrationNotFound])}
              :handler queries/list-runs}
        :post {:summary "Preview a cash account migration"
               :openapi {:operationId "PreviewCashAccountMigration"
                         :description
                         (str "Decides what the migration would do to each "
                              "account it reaches today, records a verdict "
                              "for each, and moves none. A preview can be "
                              "run again at any time, including after "
                              "approval, and counts towards any daily "
                              "preview limit the bank's policies set.")
                         :security [{"bearerAuth" ["org:developer"]}]
                         :parameters ^:replace
                                     [shared.parameters/ref-migration-id
                                      shared.parameters/ref-bank-id-header
                                      shared.parameters/ref-idempotency-key]}
               :interceptors [server/require-idempotency-key
                              bank-idempotency/cache-response]
               :responses
               (shared.idempotency/with-responses
                {201 {:description "The preview run."
                      :body [:ref "MigrationRun"]
                      :openapi {:headers {"Location" (shared.headers/location
                                                      "preview")}}}
                 404 (ErrorResponse [#'MigrationNotFound])
                 429 (ErrorResponse [#'api-schema/PolicyLimitExceeded])})
               :handler handlers/preview-migration}}]
      ["/{run-id}"
       {:parameters {:path {:run-id [:ref "MigrationRunId"]}}}
       [""
        {:openapi {:security [{"bearerAuth" ["org:viewer"]}]
                   :parameters [shared.parameters/ref-bank-id-header]}
         :get {:summary "Retrieve a migration preview"
               :openapi {:operationId "RetrieveCashAccountMigrationPreview"
                         :description
                         (str "One run of the migration, with how many "
                              "accounts it saw, moved, found ineligible and "
                              "failed. A run of another migration is refused "
                              "with 404.")}
               :responses {200 {:description "The preview run."
                                :body [:ref "MigrationRun"]}
                           404 (ErrorResponse [#'RunNotFound])}
               :handler queries/get-run}}]
       ["/accounts"
        {:openapi {:security [{"bearerAuth" ["org:viewer"]}]}
         :get {:summary "List a preview's per-account verdicts"
               :openapi {:operationId "ListCashAccountMigrationPreviewAccounts"
                         :description
                         (str "One verdict per account the run reached, in "
                              "account order, a page at a time. A preview "
                              "records each account as eligible, or "
                              "ineligible with the reason, and the run that "
                              "moves accounts records migrated or failed in "
                              "place of eligible.")
                         :parameters ^:replace
                                     [shared.parameters/ref-migration-id
                                      shared.parameters/ref-migration-run-id
                                      shared.parameters/ref-page
                                      shared.parameters/ref-bank-id-header]}
               :parameters {:query shared.parameters/page-query}
               :responses {200 {:description
                                "A page of the verdicts the run recorded."
                                :body [:ref "MigrationAccountRunList"]}
                           404 (ErrorResponse [#'RunNotFound])}
               :handler queries/list-run-accounts}}]]]]]])
