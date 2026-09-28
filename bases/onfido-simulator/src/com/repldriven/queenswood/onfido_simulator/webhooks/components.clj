(ns com.repldriven.queenswood.onfido-simulator.webhooks.components
  (:require
    [com.repldriven.queenswood.onfido-simulator.webhooks.examples :as examples]

    [com.repldriven.queenswood.onfido-simulator.schema :as schema]))

(def RegisterWebhookRequest
  [:map
   {:json-schema/example examples/RegisterWebhookRequest}
   [:url string?]
   [:events {:optional true} [:vector string?]]])

(def Webhook
  [:map
   {:json-schema/example examples/Webhook}
   [:id string?]
   [:url string?]
   [:token {:optional true} [:maybe string?]]
   [:events {:optional true} [:vector string?]]])

(def WebhookList
  [:map
   {:json-schema/example examples/WebhookList}
   [:webhooks [:vector [:ref "Webhook"]]]])

(def registry
  (schema/components-registry [#'RegisterWebhookRequest #'Webhook
                               #'WebhookList]))
