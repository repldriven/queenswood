(ns com.repldriven.queenswood.onfido-webhook.interface
  "Onfido's webhook: the event it delivers, `{payload {resource_type
  action object}}`, naming a resource and its status and never its
  results, and the signature it carries in `X-SHA2-Signature`, the
  hex HMAC-SHA256 of the raw body keyed by the webhook's token. Exposes
  the schemas and worked examples the adapter's OpenAPI surface and
  request validation read."
  (:require
    [com.repldriven.queenswood.onfido-webhook.components :as components]
    [com.repldriven.queenswood.onfido-webhook.signature :as signature]))

(def ^{:doc "The path the adapter receives Onfido's webhooks at."} path
  "/webhooks/onfido")

(def ^{:doc "The header Onfido carries a delivery's signature in."}
     signature-header
  "x-sha2-signature")

(def ^{:doc "Map of Onfido webhook schema name to Malli schema."}
     component-registry
  components/component-registry)

(def ^{:doc "Map of Onfido webhook schema name to a worked example."}
     example-registry
  components/example-registry)

(defn sign
  "The signature Onfido sends with `body`: the hex HMAC-SHA256 of its
  bytes keyed by the webhook's `token`."
  [token body]
  (signature/sign token body))

(defn verify
  "`:verified` where `header` is the signature of `body` under `token`,
  otherwise an `:idv-webhook/*` unauthorized anomaly naming why: no
  token held, no signature carried, or one that does not verify."
  [token header body]
  (signature/verify token header body))
