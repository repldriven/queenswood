(ns com.repldriven.queenswood.modulr-adapter.webhook.routes
  (:require
    [com.repldriven.queenswood.modulr-adapter.webhook.handlers :as handlers]))

(def ^:private responses
  {200 {:body map?}
   400 {:body [:ref "WebhookRejected"]}
   401 {:body [:ref "WebhookRejected"]}})

(def paths
  "Where each notification type is delivered, under the adapter's URL."
  {"PAYIN" "/webhooks/payin"
   "PAYOUT" "/webhooks/payout"
   "PAYMENT_COMPLIANCE_STATUS" "/webhooks/compliance"})

(def routes
  [["/webhooks"
    {:openapi {:tags ["Webhooks"]}}
    ["/payin"
     {:post {:summary "Receive a PAYIN notification from Modulr"
             :openapi {:operationId "Payin"}
             :parameters {:body [:ref "PayinWebhook"]}
             :responses responses
             :handler handlers/payin}}]
    ["/payout"
     {:post {:summary "Receive a PAYOUT notification from Modulr"
             :openapi {:operationId "Payout"}
             :parameters {:body [:ref "PayoutWebhook"]}
             :responses responses
             :handler handlers/payout}}]
    ["/compliance"
     {:post {:summary
             "Receive a PAYMENTCOMPLIANCESTATUS notification from Modulr"
             :openapi {:operationId "ComplianceStatus"}
             :parameters {:body [:ref "ComplianceStatusWebhook"]}
             :responses responses
             :handler handlers/compliance}}]]])
