(ns com.repldriven.queenswood.clearbank-webhook.interface
  "Malli schemas and worked examples for the ClearBank-shaped webhook
  payloads consumed by the bank. Exposes a `component-registry`
  (schema name -> Malli schema) and `example-registry` (schema name
  -> sample payload) for OpenAPI generation and validation."
  (:require
    [com.repldriven.queenswood.clearbank-webhook.components
     :as components]))

(def
  ^{:doc
    "Map of ClearBank webhook schema name to Malli schema.
  Covers TransactionSettled, TransactionRejected,
  PaymentMessageAssessmentFailed (under either spelling of its
  instruction list) and InboundCopRequestReceived plus their nested
  account/payload shapes, and `WebhookRejected`, the RFC 9457 body a
  webhook the adapter refuses is answered with."}
  component-registry
  components/component-registry)

(def
  ^{:doc
    "Map of ClearBank webhook schema name to a worked
  sample payload, used by Malli `:json-schema/example` annotations
  and the OpenAPI surface."}
  example-registry
  components/example-registry)
