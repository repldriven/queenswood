(ns com.repldriven.queenswood.zyphe-webhook.interface
  "Zyphe's webhook wire contract: Malli schemas and worked examples for
  the V2 event envelope the bank's IDV adapter receives, and the
  `X-Signature` scheme Zyphe signs every delivery with — HMAC-SHA256,
  keyed by the hex-decoded secret, over `<t>.<raw body>`. The adapter
  verifies with it and the simulator signs with it."
  (:require
    [com.repldriven.queenswood.zyphe-webhook.components :as components]
    [com.repldriven.queenswood.zyphe-webhook.signature :as signature]))

(def
  ^{:doc
    "Map of Zyphe webhook schema name to Malli schema. Covers the V2
  envelope, its source, its flow block and the custom data the adapter
  attaches to every verification request it creates."}
  component-registry
  components/component-registry)

(def
  ^{:doc
    "Map of Zyphe webhook schema name to a worked sample payload, used
  by Malli `:json-schema/example` annotations and the OpenAPI surface."}
  example-registry
  components/example-registry)

(def
  ^{:doc
    "The path, under the adapter's public URL, that Zyphe delivers a
  verification run's session webhook to. The relay installs it on every
  run it creates and the adapter serves it."}
  path
  "/webhooks/zyphe")

(def
  ^{:doc
    "How far, in milliseconds, a delivery's signed timestamp may sit
  from now before `verify` refuses it as a replay."}
  tolerance-ms
  signature/tolerance-ms)

(defn sign
  "The `X-Signature` header value, `t=<timestamp>,v0=<hex>`, for `body`
  delivered at `timestamp`, or an `:idv-webhook/secret` anomaly when
  the secret is not hexadecimal.

  Args:
  - secret: the hex-encoded webhook secret.
  - timestamp: the delivery time in epoch seconds.
  - body: the delivery's body as bytes."
  [secret timestamp body]
  (signature/sign secret timestamp body))

(defn verify
  "`{:timestamp seconds}` for a delivery whose `X-Signature` verifies
  under `secret`, or an unauthorized anomaly: the signature covers the
  timestamp and the body as delivered, the timestamp may not sit
  further than `tolerance-ms` from `now`, and the comparison takes
  constant time.

  Args:
  - secret: the hex-encoded webhook secret.
  - header: the `X-Signature` header value, or nil.
  - body: the delivery's body as the bytes received.
  - now: the current time in epoch milliseconds."
  [secret header body now]
  (signature/verify secret header body now))
