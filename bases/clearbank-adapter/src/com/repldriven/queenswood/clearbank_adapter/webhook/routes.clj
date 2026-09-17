(ns com.repldriven.queenswood.clearbank-adapter.webhook.routes
  (:require
    [com.repldriven.queenswood.clearbank-adapter.webhook.handlers
     :as handlers]))

(def ^:private rejected {:body [:ref "WebhookRejected"]})

(def routes
  [["/webhooks"
    {:openapi {:tags ["Webhooks"]}}
    ["/transaction-settled"
     {:post {:summary "Receive a TransactionSettled webhook from ClearBank"
             :openapi {:operationId "TransactionSettled"}
             :parameters {:body [:ref "TransactionSettledWebhook"]}
             :responses {200 {:body [:map
                                     [:Nonce int?]]}
                         400 rejected}
             :handler (handlers/transaction-settled nil)}}]
    ["/transaction-rejected"
     {:post {:summary "Receive a TransactionRejected webhook from ClearBank"
             :openapi {:operationId "TransactionRejected"}
             :parameters {:body [:ref "TransactionRejectedWebhook"]}
             :responses {200 {:body [:map
                                     [:Nonce int?]]}
                         400 rejected}
             :handler (handlers/transaction-rejected nil)}}]
    ["/payment-message-assessment-failed"
     {:post {:summary "Receive a PaymentMessageAssessmentFailed webhook"
             :openapi {:operationId "PaymentMessageAssessmentFailed"}
             :parameters {:body [:ref "PaymentMessageAssessmentFailedWebhook"]}
             :responses {200 {:body [:map [:Nonce int?]]} 400 rejected}
             :handler (handlers/payment-message-assessment-failed nil)}}]
    ["/inbound-held-transaction"
     {:post {:summary "Receive an InboundHeldTransaction webhook"
             :openapi {:operationId "InboundHeldTransaction"}
             :parameters {:body [:map
                                 [:Type string?]
                                 [:Version int?]
                                 [:Payload map?]
                                 [:Nonce int?]]}
             :responses {200 {:body [:map
                                     [:Nonce int?]]}
                         400 rejected}
             :handler (handlers/inbound-held-transaction nil)}}]
    ["/outbound-held-transaction"
     {:post {:summary "Receive an OutboundHeldTransaction webhook"
             :openapi {:operationId "OutboundHeldTransaction"}
             :parameters {:body [:map
                                 [:Type string?]
                                 [:Version int?]
                                 [:Payload map?]
                                 [:Nonce int?]]}
             :responses {200 {:body [:map
                                     [:Nonce int?]]}
                         400 rejected}
             :handler (handlers/outbound-held-transaction nil)}}]
    ["/inbound-cop-request-received"
     {:post {:summary "Receive an InboundCopRequestReceived webhook"
             :openapi {:operationId "InboundCopRequestReceived"}
             :parameters {:body [:ref "InboundCopRequestReceivedWebhook"]}
             :responses {200 {:body [:map
                                     [:matchResult
                                      [:enum "Match" "CloseMatch"
                                       "NoMatch" "Unavailable"]]
                                     [:actualName {:optional true}
                                      [:maybe string?]]
                                     [:reasonCode {:optional true}
                                      [:maybe string?]]
                                     [:reason {:optional true}
                                      [:maybe string?]]]}
                         400 rejected}
             :handler (handlers/inbound-cop-request-received nil)}}]]])
