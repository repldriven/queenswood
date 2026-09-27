(ns com.repldriven.queenswood.modulr-webhook.interface
  "Modulr's wire contract: Malli schemas and worked examples for the
  PAYIN, PAYOUT and PAYMENTCOMPLIANCESTATUS webhooks the payment adapter
  receives, and the HMAC `Signature` scheme Modulr authenticates with in
  both directions — over `date: <Date>\\nx-mod-nonce: <nonce>`, base64
  then URL-encoded, keyed by the API secret on a call and by the secret
  set at registration on a delivery. Registers
  `modulr-webhook/credentials`, a key id and secret taken from its
  configuration or generated at start."
  (:require
    [com.repldriven.queenswood.modulr-webhook.system]

    [com.repldriven.queenswood.modulr-webhook.components :as components]
    [com.repldriven.queenswood.modulr-webhook.signature :as signature]))

(def
  ^{:doc
    "Map of Modulr webhook schema name to Malli schema: the PAYIN, PAYOUT
  and PAYMENTCOMPLIANCESTATUS bodies, the party and scheme blocks they
  share, and `WebhookRejected`, the RFC 9457 body a refused delivery is
  answered with."}
  component-registry
  components/component-registry)

(def
  ^{:doc
    "Map of Modulr webhook schema name to an OpenAPI example object,
  `{:value sample}`."}
  example-registry
  components/example-registry)

(def
  ^{:doc
    "How far, in milliseconds, a signed request's `Date` may sit from now
  before `verify` refuses it."}
  tolerance-ms
  signature/tolerance-ms)

(defn nonce
  "A fresh `x-mod-nonce` value.

  Args: none."
  []
  (signature/nonce))

(defn headers
  "The `Authorization`, `Date` and `x-mod-nonce` headers that sign a
  request sent at `now-ms` with `nonce`, or an unauthorized anomaly when
  the credentials name an algorithm Modulr does not offer.

  Args:
  - credentials: `{:key-id :secret :algorithm}`, the algorithm one of
    `hmac-sha1`, `hmac-sha256`, `hmac-sha384`, `hmac-sha512`.
  - nonce: the request's nonce; a retry sends the first attempt's.
  - now-ms: the current time in epoch milliseconds."
  [credentials nonce now-ms]
  (signature/headers credentials nonce now-ms))

(defn verify
  "`{:verified true :nonce nonce}` when the request's `Authorization`
  verifies its `Date` and `x-mod-nonce` under `credentials`, or an
  unauthorized anomaly: unsigned, signed under another key id where the
  credentials name one, dated further than `tolerance-ms` from now, or
  not verifying. The comparison takes constant time.

  Args:
  - credentials: `{:key-id :secret}`; a nil key id accepts any.
  - headers: the request's headers, lower-case names as Ring gives them.
  - now-ms: the current time in epoch milliseconds."
  [credentials headers now-ms]
  (signature/verify credentials headers now-ms))
