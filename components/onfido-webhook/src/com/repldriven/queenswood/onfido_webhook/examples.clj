(ns com.repldriven.queenswood.onfido-webhook.examples)

(def WebhookObject
  {:id "01a0e8a1-3c2b-7e10-9c4a-805e275f8533"
   :status "approved"
   :completed_at_iso8601 "2026-09-28T12:00:00Z"
   :href
   "https://api.eu.onfido.com/v3.6/workflow_runs/01a0e8a1-3c2b-7e10-9c4a-805e275f8533"})

(def WebhookPayload
  {:resource_type "workflow_run"
   :action "workflow_run.completed"
   :object WebhookObject})

(def WebhookEvent {:payload WebhookPayload})
