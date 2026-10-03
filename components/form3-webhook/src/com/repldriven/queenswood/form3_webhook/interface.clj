(ns com.repldriven.queenswood.form3-webhook.interface
  "Form3's wire contract: Malli schemas and worked examples for the
  notification envelope Form3 delivers, `{id event_type record_type data
  ...}` with the resource as a GET returns it, and the HTTP Signatures
  scheme Form3 authenticates calls with — RSA-SHA256 over
  `(request-target)`, `host` and `date`, and on a write also
  `content-type`, a SHA-256 `digest` of the body and `content-length`.
  Registers `form3-webhook/credentials`, a key id and RSA key pair taken
  from its configuration as PEM, or from the PEM files
  `private-key-file` and `public-key-file` name, either half alone
  where that is all one side holds, or generated at start where
  neither half is given."
  (:require
    [com.repldriven.queenswood.form3-webhook.system]

    [com.repldriven.queenswood.form3-webhook.components :as components]
    [com.repldriven.queenswood.form3-webhook.raw-body :as raw-body]
    [com.repldriven.queenswood.form3-webhook.signature :as signature]))

(def
  ^{:doc
    "Map of Form3 schema name to Malli schema: `Notification`, the
  `Resource` it carries, and `NotificationRejected`, the RFC 9457 body a
  refused delivery is answered with."}
  component-registry
  components/component-registry)

(def
  ^{:doc
    "Map of Form3 schema name to an OpenAPI example object,
  `{:value sample}`."}
  example-registry
  components/example-registry)

(def
  ^{:doc
    "How far, in milliseconds, a signed request's `Date` may sit from now
  before `verify` refuses it."}
  tolerance-ms
  signature/tolerance-ms)

(def
  ^{:doc
    "Reitit interceptor that keeps a request's body and headers as they
  arrived, as `:raw-body` and `:raw-headers`, before anything parses
  them, so `verify` can check the signature and the body's digest, and
  presents a JSON:API body, `application/vnd.api+json`, to the parser as
  JSON."}
  raw-body
  raw-body/interceptor)

(defn headers
  "The headers that sign a request sent at `now-ms`: `Date` and
  `Authorization`, and on a write `Content-Type` and `Digest`. The HTTP
  client adds the `Host` and `Content-Length` the signature covers, which
  must be the `host` given and the body's length in bytes.

  Args:
  - credentials: `{:key-id :private-key}`, the private key an RSA
    `java.security.PrivateKey`.
  - request: `{:method :path :query :host :body :content-type}`; `:query`
    is the raw query string without its `?`, `:host` the `Host` header's
    value, `:body` a string or bytes, and `:content-type` defaults to
    `application/vnd.api+json`.
  - now-ms: the current time in epoch milliseconds."
  [credentials request now-ms]
  (signature/headers credentials request now-ms))

(defn verify
  "`{:verified true :key-id key-id}` when the request's `Authorization`
  signs what it must under one of `public-keys`, or an unauthorized
  anomaly: unsigned, signed under a key not held, by another algorithm,
  over too few headers, dated further than `tolerance-ms` from now, a
  write whose body does not match its `Digest`, or not verifying.

  Args:
  - public-keys: map of key id to RSA `java.security.PublicKey`.
  - request: `{:method :path :query :headers :body}`, the headers with
    lower-case names as Ring gives them and the body as it arrived.
  - now-ms: the current time in epoch milliseconds."
  [public-keys request now-ms]
  (signature/verify public-keys request now-ms))

(defn digest
  "The `Digest` header's value for `body`, `SHA-256=` and the base64 of
  its SHA-256.

  Args:
  - body: a string or bytes."
  [body]
  (signature/digest body))

(defn key-pair
  "A fresh 2048-bit RSA key pair, `{:private-key :public-key}`.

  Args: none."
  []
  (signature/key-pair))

(defn public-key-pem
  "An RSA public key as PEM, as Form3 takes it when a key is registered.

  Args:
  - public-key: a `java.security.PublicKey`."
  [public-key]
  (signature/public-key-pem public-key))
