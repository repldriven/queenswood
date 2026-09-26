(ns com.repldriven.queenswood.zyphe-webhook.components
  (:require
    [com.repldriven.queenswood.zyphe-webhook.examples :as examples]

    [com.repldriven.mono.utility.interface :refer [vname]]))

(defn- components-registry
  [vars]
  (reduce (fn [m v] (assoc m (vname v) @v)) {} vars))

(defn- examples-registry
  [vars]
  (reduce (fn [m v] (assoc m (vname v) @v)) {} vars))

(def CustomData
  [:map
   {:json-schema/example examples/CustomData}
   [:bankId {:optional true} string?]
   [:verificationId {:optional true} string?]])

(def FlowStep
  [:map
   {:json-schema/example examples/FlowStep}
   [:id string?]
   [:slug {:optional true} string?]
   [:name {:optional true} string?]
   [:type {:optional true} string?]])

(def Flow
  [:map
   {:json-schema/example examples/Flow}
   [:status {:optional true}
    [:enum "PROCESSING" "COMPLETED" "FAILED" "CANCELLED" "REVIEW" "REJECTED"]]
   [:slug {:optional true} string?]
   [:customData {:optional true} [:maybe [:ref "CustomData"]]]
   [:nextStep {:optional true} [:maybe [:ref "FlowStep"]]]])

(def EventSource
  [:map
   {:json-schema/example examples/EventSource}
   [:organizationId string?]
   [:flowId {:optional true} string?]
   [:flowResultId {:optional true} string?]])

(def WebhookEvent
  [:map
   {:json-schema/example examples/WebhookEvent}
   [:id string?]
   [:type string?]
   [:apiVersion string?]
   [:createdAt string?]
   [:recipientOrganizationId {:optional true} string?]
   [:source {:optional true} [:ref "EventSource"]]
   [:flow {:optional true} [:ref "Flow"]]])

(def component-registry
  (components-registry [#'CustomData #'FlowStep #'Flow #'EventSource
                        #'WebhookEvent]))

(def example-registry
  (examples-registry [#'examples/CustomData #'examples/FlowStep #'examples/Flow
                      #'examples/EventSource #'examples/WebhookEvent]))
