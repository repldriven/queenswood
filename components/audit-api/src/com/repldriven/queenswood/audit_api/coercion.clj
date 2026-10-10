(ns com.repldriven.queenswood.audit-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private audit-kind-enum
  (coercion/enum-coercion
   {"bank-created" :audit-kind-bank-created
    "bank-tier-changed" :audit-kind-bank-tier-changed
    "bank-status-changed" :audit-kind-bank-status-changed
    "invitation-created" :audit-kind-invitation-created
    "invitation-resent" :audit-kind-invitation-resent
    "invitation-accepted" :audit-kind-invitation-accepted
    "invitation-declined" :audit-kind-invitation-declined
    "invitation-withdrawn" :audit-kind-invitation-withdrawn
    "member-role-changed" :audit-kind-member-role-changed
    "member-removed" :audit-kind-member-removed
    "member-left" :audit-kind-member-left
    "party-created" :audit-kind-party-created
    "party-suspended" :audit-kind-party-suspended
    "party-resumed" :audit-kind-party-resumed
    "party-closed" :audit-kind-party-closed
    "party-merged" :audit-kind-party-merged
    "verification-session-opened" :audit-kind-verification-session-opened
    "cash-account-opened" :audit-kind-cash-account-opened
    "cash-account-suspended" :audit-kind-cash-account-suspended
    "cash-account-resumed" :audit-kind-cash-account-resumed
    "cash-account-close-requested" :audit-kind-cash-account-close-requested
    "cash-account-rotation-requested"
    :audit-kind-cash-account-rotation-requested
    "product-created" :audit-kind-product-created
    "product-version-drafted" :audit-kind-product-version-drafted
    "product-version-updated" :audit-kind-product-version-updated
    "product-version-published" :audit-kind-product-version-published
    "product-version-discarded" :audit-kind-product-version-discarded
    "migration-created" :audit-kind-migration-created
    "migration-approved" :audit-kind-migration-approved
    "migration-cancelled" :audit-kind-migration-cancelled
    "migration-previewed" :audit-kind-migration-previewed
    "internal-payment-submitted" :audit-kind-internal-payment-submitted
    "outbound-payment-submitted" :audit-kind-outbound-payment-submitted
    "payee-check-made" :audit-kind-payee-check-made
    "transaction-simulated" :audit-kind-transaction-simulated
    "webhook-endpoint-registered" :audit-kind-webhook-endpoint-registered
    "webhook-endpoint-updated" :audit-kind-webhook-endpoint-updated
    "webhook-endpoint-enabled" :audit-kind-webhook-endpoint-enabled
    "webhook-endpoint-disabled" :audit-kind-webhook-endpoint-disabled
    "webhook-endpoint-removed" :audit-kind-webhook-endpoint-removed
    "webhook-endpoint-secret-rotated"
    :audit-kind-webhook-endpoint-secret-rotated
    "webhook-endpoint-test-sent" :audit-kind-webhook-endpoint-test-sent
    "webhook-endpoint-deliveries-resent"
    :audit-kind-webhook-endpoint-deliveries-resent
    "job-schedule-updated" :audit-kind-job-schedule-updated
    "job-paused" :audit-kind-job-paused
    "job-resumed" :audit-kind-job-resumed
    "job-run-forced" :audit-kind-job-run-forced}
   :audit-kind-unknown))

(def audit-kind-enum-schema (:enum-schema audit-kind-enum))

(def ^:private audit-subject-kind-enum
  (coercion/enum-coercion
   {"bank" :audit-subject-kind-bank
    "invitation" :audit-subject-kind-invitation
    "member" :audit-subject-kind-member
    "party" :audit-subject-kind-party
    "verification-session" :audit-subject-kind-verification-session
    "cash-account" :audit-subject-kind-cash-account
    "product" :audit-subject-kind-product
    "migration" :audit-subject-kind-migration
    "internal-payment" :audit-subject-kind-internal-payment
    "outbound-payment" :audit-subject-kind-outbound-payment
    "payee-check" :audit-subject-kind-payee-check
    "transaction" :audit-subject-kind-transaction
    "webhook-endpoint" :audit-subject-kind-webhook-endpoint
    "job" :audit-subject-kind-job}
   :audit-subject-kind-unknown))

(def audit-subject-kind-enum-schema (:enum-schema audit-subject-kind-enum))
