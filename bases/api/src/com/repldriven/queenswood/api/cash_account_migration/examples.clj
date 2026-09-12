(ns com.repldriven.queenswood.api.cash-account-migration.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def MigrationNotFound
  {:value {:title "REJECTED"
           :type "cash-account-migration/not-found"
           :status 404
           :detail "Migration not found"}})

(def RunNotFound
  {:value {:title "REJECTED"
           :type "cash-account-migration/run-not-found"
           :status 404
           :detail "Migration run not found"}})

(def ProductTypeMismatch
  {:value {:title "REJECTED"
           :type "cash-account-migration/product-type-mismatch"
           :status 422
           :detail "Source and target must be the same product type"}})

(def TargetNotPublished
  {:value {:title "REJECTED"
           :type "cash-account-migration/target-not-published"
           :status 422
           :detail "A migration's target version must be published"}})

(def TargetIsSource
  {:value {:title "REJECTED"
           :type "cash-account-migration/target-is-source"
           :status 422
           :detail "A migration's target must differ from its source"}})

(def NameRequired
  {:value {:title "REJECTED"
           :type "cash-account-migration/name-required"
           :status 422
           :detail "A migration needs a name"}})

(def NoticeAfterDue
  {:value {:title "REJECTED"
           :type "cash-account-migration/notice-after-due"
           :status 422
           :detail "Customers must be notified before accounts move"}})

(def SourceProductNotFound
  {:value {:title "REJECTED"
           :type "cash-account-migration/source-product-not-found"
           :status 404
           :detail "Source product has no versions"}})

(def InvalidStatus
  {:value {:title "REJECTED"
           :type "cash-account-migration/invalid-status"
           :status 409
           :detail "Migration cannot be approved"}})

(def NoticeRequired
  {:value {:title "REJECTED"
           :type "cash-account-migration/notice-required"
           :status 422
           :detail
           "A migration needs a notice date and a due date before approval"}})

(def registry
  (examples-registry [#'MigrationNotFound #'RunNotFound #'ProductTypeMismatch
                      #'TargetNotPublished #'TargetIsSource #'NoticeAfterDue
                      #'NameRequired #'SourceProductNotFound #'InvalidStatus
                      #'NoticeRequired]))

(def MigrationId "mig.01kz3wyzcjhkab9ch91x9ngedr")

(def RunId "01940000-0000-7000-8000-000000000000")

(def Migration
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :migration-id MigrationId
   :status :cash-account-migration-status-draft
   :name "Super-saver to mega-saver"
   :source-product-id "prd.01kz3wyzcjhkab9ch91x9ngedr"
   :source-version-ids ["prv.01kz3wyz91pf6z2zgfv9pxpm49"]
   :target-product-id "prd.01kz3wyz91pf6z2zgfv9pxpm49"
   :target-version-id "prv.01kz3wyzcjhkab9ch91x9ngedr"
   :notified-on "2026-06-01"
   :due-on "2026-08-01"
   :created-at 1735783200000
   :updated-at 1735783200000})

(def MigrationList {:migrations [Migration]})

(def MigrationCreate
  {:name "Super-saver to mega-saver"
   :source-product-id "prd.01kz3wyzcjhkab9ch91x9ngedr"
   :target-product-id "prd.01kz3wyz91pf6z2zgfv9pxpm49"
   :target-version-id "prv.01kz3wyzcjhkab9ch91x9ngedr"
   :notified-on "2026-06-01"
   :due-on "2026-08-01"})

(def MigrationRun
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :run-id RunId
   :migration-id MigrationId
   :status :cash-account-migration-run-status-completed
   :dry-run true
   :business-day "2026-06-01"
   :started-at 1735783200000
   :finished-at 1735783230000
   :accounts-seen 9588
   :accounts-moved 9176
   :accounts-ineligible 412
   :accounts-failed 0})

(def MigrationRunList {:runs [MigrationRun]})

(def MigrationAccountRun
  {:bank-id "bnk.01kprbmgcj35ptc8npmybhh4s7"
   :run-id RunId
   :migration-id MigrationId
   :account-id "acc.01kz3wyzcjhkab9ch91x9ngedr"
   :outcome :cash-account-migration-outcome-ineligible
   :from-version-id "prv.01kz3wyz91pf6z2zgfv9pxpm49"
   :ineligibility :cash-account-migration-ineligibility-currency-not-allowed
   :created-at 1735783210000})

(def MigrationAccountRunList {:accounts [MigrationAccountRun]})
