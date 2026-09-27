(ns com.repldriven.queenswood.clearbank-webhook.interface
  "Malli schemas and worked examples for the ClearBank-shaped webhook
  payloads consumed by the bank, and the `DigitalSignature` scheme
  ClearBank signs with in both directions — RSA with SHA-256 over the
  raw body, base64-encoded. The adapter signs what it sends ClearBank
  and verifies what ClearBank sends it; the simulator does the reverse.
  Registers `clearbank-webhook/key-pair`, an RSA key pair generated at
  start, which stands in for each side's key."
  (:require
    [com.repldriven.queenswood.clearbank-webhook.system]

    [com.repldriven.queenswood.clearbank-webhook.components
     :as components]
    [com.repldriven.queenswood.clearbank-webhook.raw-body :as raw-body]
    [com.repldriven.queenswood.clearbank-webhook.signature :as signature]))

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

(def ^{:doc "The header each signed request and webhook carries."}
     signature-header
  signature/header)

(defn key-pair
  "A fresh 2048-bit RSA key pair, `{:private-key :public-key}`.

  Args: none."
  []
  (signature/key-pair))

(defn sign
  "The `DigitalSignature` value for `body`: its SHA-256 RSA signature,
  base64-encoded, or a `:payment-webhook/sign` anomaly.

  Args:
  - private-key: the signer's `java.security.PrivateKey`.
  - body: the body as bytes or a string, exactly as sent."
  [private-key body]
  (signature/sign private-key body))

(defn verify
  "`{:verified true}` when `signature` verifies `body` under
  `public-key`, or an unauthorized anomaly: `:payment-webhook/no-key`,
  `:payment-webhook/unsigned` or `:payment-webhook/invalid-signature`.

  Args:
  - public-key: the sender's `java.security.PublicKey`, or nil.
  - signature: the `DigitalSignature` header value, or nil.
  - body: the body as bytes or a string, exactly as received."
  [public-key signature body]
  (signature/verify public-key signature body))

(def
  ^{:doc
    "Keeps a request's body as it arrived, as `:raw-body`, before
  anything decodes it: the signature covers those bytes and no
  re-encoding of them. First in a router's chain, so it runs ahead of
  the request decoder."}
  raw-body
  raw-body/interceptor)
