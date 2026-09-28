(ns com.repldriven.queenswood.onfido-adapter.webhook.routes
  (:require
    [com.repldriven.queenswood.onfido-adapter.webhook.handlers :as handlers]

    [com.repldriven.queenswood.onfido-webhook.interface :as onfido-webhook]))

(def routes
  [[onfido-webhook/path
    {:openapi {:tags ["Webhooks"]}
     :post {:summary "Receive a signed Onfido webhook"
            :openapi {:operationId "OnfidoWebhook"}
            :parameters {:header [:map
                                  [:x-sha2-signature {:optional true}
                                   string?]]
                         :body [:ref "WebhookEvent"]}
            :responses {200 {:body [:map [:received boolean?]]}
                        401 {:body [:map [:error string?]]}
                        500 {:body [:map [:error string?]]}}
            :handler (handlers/receive nil)}}]])
