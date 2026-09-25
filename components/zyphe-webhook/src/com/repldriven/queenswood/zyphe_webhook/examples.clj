(ns com.repldriven.queenswood.zyphe-webhook.examples)

(def CustomData {:bankId "bnk.01kbx3" :verificationId "idv.01kbx3"})

(def FlowStep
  {:id "03559a0f-be8e-4ab7-964d-1ff4745d92a4"
   :slug "liveness"
   :name "Liveness"
   :type "LIVENESS"})

(def Flow {:status "COMPLETED" :slug "onboarding" :customData CustomData})

(def EventSource
  {:organizationId "6b0f6a2e-1f5c-4a70-9d6b-2b1a0e4c9d33"
   :flowId "2d8285d7-f4ba-42df-ab3f-681d9870d37a"
   :flowResultId "936f35a8-4921-430e-b016-15be48968886"})

(def WebhookEvent
  {:id "0f0dcb6c-6b6a-4f4a-a4a6-2b4a19e2e9c1"
   :type "flow.completed"
   :apiVersion "2026-08-26"
   :createdAt "2026-08-26T10:12:44.192Z"
   :recipientOrganizationId "6b0f6a2e-1f5c-4a70-9d6b-2b1a0e4c9d33"
   :source EventSource
   :flow Flow})
