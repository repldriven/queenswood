(ns com.repldriven.queenswood.zyphe-simulator.verification-requests.examples)

(def Credential {:type "EXTERNAL_ID" :externalId "pty.01kbx3"})

(def SessionWebhookRequest
  {:url "http://localhost:8087/webhooks/zyphe"
   :secret "9f2b7c1d4e6a8035b1c9d2e4f6a80351d7e9b2c4f6a803517d9e2b4c6f8a0351"
   :payloadVersion "V2"})

(def CreateVerificationRequest
  {:credentials [Credential]
   :customData {:bankId "bnk.01kbx3" :verificationId "idv.01kbx3"}
   :webhook SessionWebhookRequest})

(def VerificationRequest
  {:id "cf52e18e-28d1-4a2f-8304-c04ef5a75d0f"
   :identityId "3f7a1c62-9f0d-4b58-b1d2-6b8a9c0e5d41"
   :flowId "2d8285d7-f4ba-42df-ab3f-681d9870d37a"
   :flowStepId "03559a0f-be8e-4ab7-964d-1ff4745d92a4"
   :organizationId "6b0f6a2e-1f5c-4a70-9d6b-2b1a0e4c9d33"
   :customData {:bankId "bnk.01kbx3" :verificationId "idv.01kbx3"}
   :status "PENDING"
   :attemptsCount 0
   :createdAt "2026-09-25T12:00:00Z"})

(def SessionWebhook
  {:id "8a1c0d33-59a2-4f6c-9c30-64e1c1a4f0f2"
   :secretHint "…0351"
   :expiresAt "2026-10-25T12:00:00Z"})

(def CreateVerificationRequestResponse
  {:verificationRequest VerificationRequest
   :zid "EXTERNAL_ID:pty.01kbx3"
   :zypheToken "simulated-token"
   :zypheAccessSig "simulated-signature"
   :flowSlug "onboarding"
   :flowStepSlug "document-verification"
   :isSandbox true
   :sessionId "cbe0f5cd-6f38-4a2e-9c3f-0a2f9e2b7c14"
   :sessionWebhook SessionWebhook})

(def Decision {:flowStatus "REJECTED"})
