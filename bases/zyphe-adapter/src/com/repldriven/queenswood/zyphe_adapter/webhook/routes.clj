(ns com.repldriven.queenswood.zyphe-adapter.webhook.routes
  (:require
    [com.repldriven.queenswood.zyphe-adapter.webhook.handlers :as handlers]

    [com.repldriven.queenswood.zyphe-webhook.interface :as zyphe-webhook]))

(def routes
  [[zyphe-webhook/path
    {:openapi {:tags ["Webhooks"]}
     :post {:summary "Receive a signed Zyphe session webhook"
            :openapi {:operationId "ZypheWebhook"}
            :parameters {:header [:map
                                  [:x-signature {:optional true}
                                   string?]]
                         :body [:ref "WebhookEvent"]}
            :responses {200 {:body [:map [:received boolean?]]}
                        401 {:body [:map [:error string?]]}}
            :handler (handlers/receive nil)}}]])
