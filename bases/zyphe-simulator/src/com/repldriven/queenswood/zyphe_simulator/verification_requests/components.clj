(ns com.repldriven.queenswood.zyphe-simulator.verification-requests.components
  (:require
    [com.repldriven.queenswood.zyphe-simulator.verification-requests.examples
     :as examples]

    [com.repldriven.queenswood.zyphe-simulator.schema :as schema]))

(def flow-statuses
  ["PROCESSING" "COMPLETED" "FAILED" "CANCELLED" "REVIEW" "REJECTED"])

(def Credential
  [:map
   {:json-schema/example examples/Credential}
   [:type [:enum "EXTERNAL_ID" "WALLET"]]
   [:externalId {:optional true} string?]
   [:chain {:optional true} string?]
   [:address {:optional true} string?]])

(def SessionWebhookRequest
  [:map
   {:json-schema/example examples/SessionWebhookRequest}
   [:url string?]
   [:secret [:re #"^[0-9a-fA-F]{32,512}$"]]
   [:eventTypes {:optional true} [:maybe [:vector string?]]]
   [:payloadVersion {:optional true} [:maybe [:enum "V2" "LEGACY_V1"]]]])

(def CreateVerificationRequest
  [:map
   {:json-schema/example examples/CreateVerificationRequest}
   [:email {:optional true} [:maybe string?]]
   [:credentials {:optional true} [:maybe [:vector [:ref "Credential"]]]]
   [:customData {:optional true} [:maybe [:map-of :keyword any?]]]
   [:webhook {:optional true} [:maybe [:ref "SessionWebhookRequest"]]]])

(def VerificationRequest
  [:map
   {:json-schema/example examples/VerificationRequest}
   [:id string?]
   [:identityId string?]
   [:flowId string?]
   [:flowStepId string?]
   [:organizationId string?]
   [:customData [:map-of :keyword any?]]
   [:status
    [:enum "PENDING" "COMPLETED" "FAILED" "CANCELLED" "QUEUED" "PROCESSING"
     "REQUIRES_MANUAL_REVIEW" "REQUIRES_ADMIN_REVIEW" "REJECTED"]]
   [:attemptsCount int?]
   [:createdAt {:optional true} [:maybe string?]]])

(def SessionWebhook
  [:map
   {:json-schema/example examples/SessionWebhook}
   [:id string?]
   [:secretHint string?]
   [:expiresAt string?]])

(def CreateVerificationRequestResponse
  [:map
   {:json-schema/example examples/CreateVerificationRequestResponse}
   [:verificationRequest [:ref "VerificationRequest"]]
   [:zid string?]
   [:zypheToken string?]
   [:zypheAccessSig string?]
   [:flowSlug string?]
   [:flowStepSlug string?]
   [:isSandbox boolean?]
   [:email {:optional true} [:maybe string?]]
   [:sessionId {:optional true} string?]
   [:sessionWebhook {:optional true} [:ref "SessionWebhook"]]])

(def Decision
  [:map
   {:json-schema/example examples/Decision}
   [:flowStatus (into [:enum] (remove #{"PROCESSING"} flow-statuses))]])

(def registry
  (assoc (schema/components-registry
          [#'Credential #'SessionWebhookRequest #'CreateVerificationRequest
           #'VerificationRequest #'SessionWebhook
           #'CreateVerificationRequestResponse #'Decision])
         "BaxeError"
         schema/BaxeError))
