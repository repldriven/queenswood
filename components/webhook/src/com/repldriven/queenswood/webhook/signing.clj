(ns com.repldriven.queenswood.webhook.signing
  (:require
    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str])
  (:import
    (javax.crypto Mac)
    (javax.crypto.spec SecretKeySpec)
    (java.nio.charset StandardCharsets)
    (java.util Base64)))

(def
  ^{:doc
    "The prefix a webhook secret carries. Standard Webhooks writes the
  key material as base64 after it, and the signature is keyed by those
  bytes rather than by the printable form."}
  secret-prefix
  "whsec_")

(def ^{:doc "The signature scheme's version tag, as it appears in the header."}
     scheme-version
  "v1")

(def ^{:doc "The header carrying the message id the signature covers."}
     id-header
  "webhook-id")

(def ^{:doc
       "The header carrying the timestamp the signature covers, in seconds."}
     timestamp-header
  "webhook-timestamp")

(def
  ^{:doc
    "The header carrying the signatures, space-separated. A delivery
  inside a rotation window carries two."}
  signature-header
  "webhook-signature")

(def ^:private hmac-algorithm "HmacSHA256")

(defn- key-bytes
  "The secret's key material. The printable form is base64 after the
  prefix; the URL-safe alphabet is translated into the standard one
  first, so a secret minted either way keys the same HMAC."
  [secret]
  (-> secret
      (str/replace-first (re-pattern (str "^" secret-prefix)) "")
      (str/replace "-" "+")
      (str/replace "_" "/")
      (->> (.decode (Base64/getDecoder)))))

(defn- ->bytes
  [^String s]
  (.getBytes s StandardCharsets/UTF_8))

(defn signed-content
  "The bytes a signature covers: the message id, the timestamp in
  seconds and the body, joined by dots, as the Standard Webhooks
  specification defines them.

  `body` is the rendered notification, already the bytes the request
  carries — a delivery and every re-send of it sign the same ones."
  [message-id timestamp-seconds ^bytes body]
  (let [prefix (->bytes (str message-id "." timestamp-seconds "."))
        out (byte-array (+ (alength prefix) (alength body)))]
    (System/arraycopy prefix 0 out 0 (alength prefix))
    (System/arraycopy body 0 out (alength prefix) (alength body))
    out))

(defn sign
  "One signature over `body` under `secret`, in the header's
  `v1,<base64>` form, or an `:webhook/signing` anomaly when the secret
  is not a key this can read."
  [secret message-id timestamp-seconds body]
  (error/try-nom
   :webhook/signing
   "Failed to sign webhook delivery"
   (let [mac (doto (Mac/getInstance hmac-algorithm)
               (.init (SecretKeySpec. (key-bytes secret) hmac-algorithm)))
         digest (.doFinal mac
                          (signed-content message-id
                                          timestamp-seconds
                                          body))]
     (str scheme-version
          ","
          (.encodeToString (Base64/getEncoder) digest)))))

(defn- rotation-secret
  "The rotated-away secret, while it is still inside its window."
  [{:keys [previous-secret previous-secret-expires-at]} now]
  (when (and previous-secret
             previous-secret-expires-at
             (< now previous-secret-expires-at))
    previous-secret))

(defn headers
  "The Standard Webhooks headers for one delivery of `body` to
  `endpoint`, or an anomaly. Inside a rotation window the signature
  header carries the current and the previous secret's signatures,
  space-separated, so a tenant verifying under either one accepts.

  `now` is epoch-ms; the timestamp header is seconds, which is what the
  specification signs and what a tenant's library expects."
  [endpoint message-id body now]
  (let [seconds (quot now 1000)
        secrets (cond-> [(:secret endpoint)]
                        (rotation-secret endpoint now)
                        (conj (rotation-secret endpoint now)))
        signatures (mapv #(sign % message-id seconds body) secrets)]
    (or (first (filter error/anomaly? signatures))
        {id-header message-id
         timestamp-header (str seconds)
         signature-header (str/join " " signatures)})))
