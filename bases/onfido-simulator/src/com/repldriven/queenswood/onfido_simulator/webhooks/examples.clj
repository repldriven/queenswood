(ns com.repldriven.queenswood.onfido-simulator.webhooks.examples)

(def RegisterWebhookRequest
  {:url "http://onfido-adapter:8080/webhooks/onfido"
   :events ["workflow_run.completed"]})

(def Webhook
  {:id "wh_aaaa-bbbb-cccc-dddd"
   :url "http://onfido-adapter:8080/webhooks/onfido"
   :token "onfido-webhook-token-simulated"
   :events ["workflow_run.completed"]})

(def WebhookList {:webhooks [Webhook]})
