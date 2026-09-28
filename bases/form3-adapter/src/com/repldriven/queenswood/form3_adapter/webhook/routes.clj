(ns com.repldriven.queenswood.form3-adapter.webhook.routes
  (:require
    [com.repldriven.queenswood.form3-adapter.webhook.handlers :as handlers]))

(def path
  "Where every notification is delivered, under the adapter's URL."
  "/webhooks/form3")

(def subscriptions
  "The record and event types the adapter subscribes to."
  [["payment_submissions" "updated"]
   ["payment_admission_tasks" "created"]
   ["payment_admissions" "updated"]
   ["return_admissions" "created"]])

(def routes
  [[path
    {:openapi {:tags ["Webhooks"]}
     :post {:summary "Receive a notification from Form3"
            :openapi {:operationId "Notification"}
            :parameters {:body [:ref "Notification"]}
            :responses {200 {:body map?}
                        400 {:body [:ref "NotificationRejected"]}
                        500 {:body map?}}
            :handler handlers/notification}}]])
