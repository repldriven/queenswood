(ns com.repldriven.queenswood.onfido-webhook.components
  (:require
    [com.repldriven.queenswood.onfido-webhook.examples :as examples]

    [com.repldriven.mono.utility.interface :refer [vname]]))

(defn- components-registry
  [vars]
  (reduce (fn [m v] (assoc m (vname v) @v)) {} vars))

(defn- examples-registry
  [vars]
  (reduce (fn [m v] (assoc m (vname v) @v)) {} vars))

(def WebhookObject
  [:map
   {:json-schema/example examples/WebhookObject}
   [:id string?]
   [:status {:optional true} [:maybe string?]]
   [:completed_at_iso8601 {:optional true} [:maybe string?]]
   [:href {:optional true} [:maybe string?]]])

(def WebhookPayload
  [:map
   {:json-schema/example examples/WebhookPayload}
   [:resource_type string?]
   [:action string?]
   [:object [:ref "WebhookObject"]]])

(def WebhookEvent
  [:map
   {:json-schema/example examples/WebhookEvent}
   [:payload [:ref "WebhookPayload"]]])

(def component-registry
  (components-registry [#'WebhookObject #'WebhookPayload #'WebhookEvent]))

(def example-registry
  (examples-registry [#'examples/WebhookObject #'examples/WebhookPayload
                      #'examples/WebhookEvent]))
