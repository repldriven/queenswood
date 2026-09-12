(ns com.repldriven.queenswood.webhook.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer [examples-registry]]
    [com.repldriven.queenswood.cash-account-api.interface :as
     cash-account-api]))

(def WebhookEndpointId "whe.01kprbmgcj35ptc8npmybhh4sg")

(def WebhookDeliveryId "whd.01kprbmgcj35ptc8npmybhh4sh")

(def WebhookNotificationId "whn.01kprbmgcj35ptc8npmybhh4sj")

(def WebhookSigningSecret "whsec_9Qk3sVQm0d1tYf8pZr2XwLbN7cJhGeAu5KiRoT4MzSY")

(def endpoint
  "One enabled endpoint, as every read route returns it. No secret: the
  secret leaves the bank in the registration and rotation responses and
  nowhere else."
  {:bank-id (:bank-id cash-account-api/CashAccount)
   :endpoint-id WebhookEndpointId
   :address "https://hooks.example.com/queenswood"
   :description "Ledger sync"
   :kinds ["cash-account.opened"]
   :status :enabled
   :last-success-at "2026-05-18T09:15:04Z"
   :created-at "2026-05-01T00:00:00Z"
   :updated-at "2026-05-18T09:15:04Z"})

(def delivery
  "One delivered delivery, as the delivery history returns it."
  {:bank-id (:bank-id cash-account-api/CashAccount)
   :delivery-id WebhookDeliveryId
   :notification-id WebhookNotificationId
   :endpoint-id WebhookEndpointId
   :status :delivered
   :kind "cash-account.opened"
   :attempts 1
   :last-response-status 200
   :created-at "2026-05-18T09:15:00Z"
   :updated-at "2026-05-18T09:15:04Z"})

(def notification
  "One `cash-account.opened` notification, carrying the account
  exactly as `GET /v1/cash-accounts/{id}` returns it — the same
  example, so the envelope cannot drift from the read route's body."
  {:notification-id WebhookNotificationId
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

(def WebhookEndpointRequest
  {:address (:address endpoint)
   :description (:description endpoint)
   :kinds (:kinds endpoint)})

(def WebhookEndpointRegistration
  {:endpoint endpoint :secret WebhookSigningSecret})

(def WebhookEndpointSecretRotation
  {:endpoint endpoint
   :secret WebhookSigningSecret
   :previous-secret-expires-at "2026-05-19T09:15:04Z"})

(def WebhookEndpointList {:items [endpoint]})

(def WebhookDeliveryList {:items [delivery]})

(def WebhookEndpointEnableRequest {:since "2026-05-17T00:00:00Z"})

(def WebhookResendWindowRequest
  {:from "2026-05-17T00:00:00Z" :to "2026-05-18T00:00:00Z"})

(def WebhookNotification {:value notification})

(def WebhookEndpointNotFound
  {:value {:title "REJECTED"
           :type ":webhook-endpoint/not-found"
           :status 404
           :detail "Webhook endpoint not found"}})

(def WebhookDeliveryNotFound
  {:value {:title "REJECTED"
           :type ":webhook-delivery/not-found"
           :status 404
           :detail "Webhook delivery not found"}})

(def WebhookEndpointInvalidAddress
  {:value {:title "REJECTED"
           :type ":webhook-endpoint/invalid-address"
           :status 422
           :detail "Webhook address is not a valid destination"}})

(def WebhookEndpointInvalidStatus
  {:value {:title "REJECTED"
           :type ":webhook-endpoint/invalid-status"
           :status 409
           :detail "Endpoint is not in a state that allows this"}})

(def registry
  (examples-registry [#'WebhookNotification #'WebhookEndpointNotFound
                      #'WebhookDeliveryNotFound #'WebhookEndpointInvalidAddress
                      #'WebhookEndpointInvalidStatus]))

(def WebhookNotificationKind (:kind notification))
