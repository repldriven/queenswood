(ns com.repldriven.queenswood.access-api.coercion
  (:require
    [com.repldriven.queenswood.api-schema.interface :as coercion]))

(def ^:private invitation-status-enum
  (coercion/enum-coercion {"pending" :invitation-status-pending
                           "accepted" :invitation-status-accepted
                           "declined" :invitation-status-declined
                           "withdrawn" :invitation-status-withdrawn
                           "expired" :invitation-status-expired}
                          :invitation-status-unknown))

(def invitation-status-enum-schema (:enum-schema invitation-status-enum))

(def ^:private audit-event-kind-enum
  (coercion/enum-coercion
   {"bank-created" :audit-event-kind-bank-created
    "bank-tier-changed" :audit-event-kind-bank-tier-changed
    "bank-status-changed" :audit-event-kind-bank-status-changed
    "invitation-created" :audit-event-kind-invitation-created
    "invitation-resent" :audit-event-kind-invitation-resent
    "invitation-accepted" :audit-event-kind-invitation-accepted
    "invitation-declined" :audit-event-kind-invitation-declined
    "invitation-withdrawn" :audit-event-kind-invitation-withdrawn
    "member-role-changed" :audit-event-kind-member-role-changed
    "member-removed" :audit-event-kind-member-removed
    "member-left" :audit-event-kind-member-left
    "party-created" :audit-event-kind-party-created
    "party-suspended" :audit-event-kind-party-suspended
    "party-resumed" :audit-event-kind-party-resumed
    "party-closed" :audit-event-kind-party-closed
    "party-merged" :audit-event-kind-party-merged
    "verification-session-opened" :audit-event-kind-verification-session-opened
    "cash-account-opened" :audit-event-kind-cash-account-opened
    "cash-account-suspended" :audit-event-kind-cash-account-suspended
    "cash-account-resumed" :audit-event-kind-cash-account-resumed
    "cash-account-close-requested"
    :audit-event-kind-cash-account-close-requested
    "cash-account-rotation-requested"
    :audit-event-kind-cash-account-rotation-requested
    "product-created" :audit-event-kind-product-created
    "product-version-drafted" :audit-event-kind-product-version-drafted
    "product-version-updated" :audit-event-kind-product-version-updated
    "product-version-published" :audit-event-kind-product-version-published
    "product-version-discarded" :audit-event-kind-product-version-discarded
    "migration-created" :audit-event-kind-migration-created
    "migration-approved" :audit-event-kind-migration-approved
    "migration-cancelled" :audit-event-kind-migration-cancelled
    "migration-previewed" :audit-event-kind-migration-previewed
    "internal-payment-submitted" :audit-event-kind-internal-payment-submitted
    "outbound-payment-submitted" :audit-event-kind-outbound-payment-submitted
    "payee-check-made" :audit-event-kind-payee-check-made
    "transaction-simulated" :audit-event-kind-transaction-simulated
    "webhook-endpoint-registered" :audit-event-kind-webhook-endpoint-registered
    "webhook-endpoint-updated" :audit-event-kind-webhook-endpoint-updated
    "webhook-endpoint-enabled" :audit-event-kind-webhook-endpoint-enabled
    "webhook-endpoint-disabled" :audit-event-kind-webhook-endpoint-disabled
    "webhook-endpoint-removed" :audit-event-kind-webhook-endpoint-removed
    "webhook-endpoint-secret-rotated"
    :audit-event-kind-webhook-endpoint-secret-rotated
    "webhook-endpoint-test-sent" :audit-event-kind-webhook-endpoint-test-sent
    "webhook-endpoint-deliveries-resent"
    :audit-event-kind-webhook-endpoint-deliveries-resent
    "job-schedule-updated" :audit-event-kind-job-schedule-updated
    "job-paused" :audit-event-kind-job-paused
    "job-resumed" :audit-event-kind-job-resumed
    "job-run-forced" :audit-event-kind-job-run-forced}
   :audit-event-kind-unknown))

(def audit-event-kind-enum-schema (:enum-schema audit-event-kind-enum))

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
