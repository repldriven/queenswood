(ns com.repldriven.queenswood.webhook.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer [examples-registry]]
    [com.repldriven.queenswood.cash-account-api.interface :as
     cash-account-api]))

(def notification
  "One `cash-account.opened` notification, carrying the account
  exactly as `GET /v1/cash-accounts/{id}` returns it — the same
  example, so the envelope cannot drift from the read route's body."
  {:notification-id "01998b6e-0e2e-7c3a-9a1e-5f6d2c4b8a01"
   :kind "cash-account.opened"
   :change-kind "open"
   :occurred-at "2026-05-18T09:15:00Z"
   :bank-id (:bank-id cash-account-api/CashAccount)
   :resource-type "CashAccount"
   :resource-id (:account-id cash-account-api/CashAccount)
   :status-before "opening"
   :status-after "opened"
   :idempotency-key "9f1c8b7a-5d4e-4c3b-9a2f-1e0d8c7b6a54"
   :correlation-id "01998b6e-0e2e-7c3a-9a1e-5f6d2c4b8a02"
   :data cash-account-api/CashAccount})

(def WebhookNotification {:value notification})

(def registry (examples-registry [#'WebhookNotification]))

(def WebhookNotificationId (:notification-id notification))

(def WebhookNotificationKind (:kind notification))
