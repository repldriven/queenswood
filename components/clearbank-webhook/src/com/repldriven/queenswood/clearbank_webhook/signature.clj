(ns com.repldriven.queenswood.clearbank-webhook.signature
  (:require
    [com.repldriven.mono.encryption.interface :as encryption]
    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str])
  (:import
    (java.nio.charset StandardCharsets)
    (java.security KeyPairGenerator Signature)
    (java.util Base64)))

(def header "DigitalSignature")

(def ^:private algorithm "SHA256withRSA")

(defn key-pair
  []
  (let [pair (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                 (.initialize 2048)))]
    {:private-key (.getPrivate pair) :public-key (.getPublic pair)}))

(defn- pem-bytes
  ^bytes [pem]
  (.decode (Base64/getMimeDecoder) (str/replace pem #"-----[A-Z ]+-----" "")))

(defn private-key
  [pem]
  (encryption/private-key-pkcs8-encoded->rsa (pem-bytes pem)))

(defn public-key
  [pem]
  (encryption/public-key-x509-encoded->rsa (pem-bytes pem)))

(defn- ->bytes
  ^bytes [body]
  (if (string? body) (.getBytes ^String body StandardCharsets/UTF_8) body))

(defn sign
  [private-key body]
  (error/try-nom
   :payment-webhook/sign
   "the body could not be signed"
   (let [signer (doto (Signature/getInstance algorithm)
                  (.initSign private-key)
                  (.update (->bytes body)))]
     (.encodeToString (Base64/getEncoder) (.sign signer)))))

(defn- unverified
  [kind message]
  (error/unauthorized kind {:message message}))

(defn- verifies?
  [public-key signature body]
  (true? (error/try-nom
          :payment-webhook/invalid-signature
          "the signature could not be decoded"
          (.verify (doto (Signature/getInstance algorithm)
                     (.initVerify public-key)
                     (.update (->bytes body)))
                   (.decode (Base64/getDecoder) ^String signature)))))

(defn verify
  [public-key signature body]
  (cond
   (nil? public-key)
   (unverified :payment-webhook/no-key "no key to verify the signature with")

   (str/blank? signature)
   (unverified :payment-webhook/unsigned "the request carries no signature")

   (verifies? public-key (str/trim signature) body)
   {:verified true}

   :else
   (unverified :payment-webhook/invalid-signature
               "the request's signature does not verify")))
