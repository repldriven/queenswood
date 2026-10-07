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
   [:verificationId {:optional true} string?]
   [:partyId {:optional true} string?]])

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

(def DocumentVerification
  [:map
   {:json-schema/example examples/DocumentVerification}
   [:id {:optional true} string?]
   [:verificationRequestId {:optional true} string?]
   [:status {:optional true} string?]
   [:reasons {:optional true} [:maybe [:vector string?]]]
   [:documentType {:optional true} [:maybe string?]]
   [:customData {:optional true} [:maybe [:ref "CustomData"]]]])

(def ExtractedDocument
  [:map
   {:json-schema/example examples/ExtractedDocument}
   [:firstName {:optional true} [:maybe string?]]
   [:lastName {:optional true} [:maybe string?]]
   [:dateOfBirth {:optional true} [:maybe string?]]
   [:issuingState {:optional true} [:maybe string?]]
   [:documentClassCode {:optional true} [:maybe string?]]])

(def ProofOfAddress
  [:map
   {:json-schema/example examples/ProofOfAddress}
   [:id {:optional true} string?]
   [:status {:optional true} string?]
   [:reason {:optional true} [:maybe string?]]
   [:documentType {:optional true} [:maybe string?]]
   [:customData {:optional true} [:maybe [:ref "CustomData"]]]])

(def AmlScreening
  [:map
   {:json-schema/example examples/AmlScreening}
   [:id {:optional true} string?]
   [:updateKind {:optional true} string?]
   [:status {:optional true} string?]
   [:subjectType {:optional true} string?]
   [:hasPep {:optional true} boolean?]
   [:hasSanctions {:optional true} boolean?]
   [:riskScorePercent {:optional true} [:maybe int?]]
   [:customData {:optional true} [:maybe [:ref "CustomData"]]]])

(def EventData
  [:map
   {:json-schema/example examples/EventData}
   [:dv {:optional true} [:maybe [:ref "DocumentVerification"]]]
   [:additionalData {:optional true} [:maybe [:ref "ExtractedDocument"]]]
   [:poa {:optional true} [:maybe [:ref "ProofOfAddress"]]]
   [:aml {:optional true} [:maybe [:ref "AmlScreening"]]]
   [:identityId {:optional true} [:maybe string?]]])

(def WebhookEvent
  [:map
   {:json-schema/example examples/WebhookEvent}
   [:id string?]
   [:type string?]
   [:apiVersion string?]
   [:createdAt string?]
   [:recipientOrganizationId {:optional true} string?]
   [:source {:optional true} [:ref "EventSource"]]
   [:flow {:optional true} [:ref "Flow"]]
   [:data {:optional true} [:maybe [:ref "EventData"]]]])

(def component-registry
  (components-registry [#'CustomData #'FlowStep #'Flow #'EventSource
                        #'DocumentVerification #'ExtractedDocument
                        #'ProofOfAddress #'AmlScreening #'EventData
                        #'WebhookEvent]))

(def example-registry
  (examples-registry [#'examples/CustomData #'examples/FlowStep #'examples/Flow
                      #'examples/EventSource #'examples/DocumentVerification
                      #'examples/ExtractedDocument #'examples/ProofOfAddress
                      #'examples/AmlScreening #'examples/EventData
                      #'examples/WebhookEvent]))
