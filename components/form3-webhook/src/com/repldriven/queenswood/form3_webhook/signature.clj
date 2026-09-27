(ns com.repldriven.queenswood.form3-webhook.signature
  (:require
    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str])
  (:import
    (java.nio.charset StandardCharsets)
    (java.security KeyFactory
                   KeyPairGenerator
                   MessageDigest
                   PrivateKey
                   PublicKey
                   Signature)
    (java.security.spec PKCS8EncodedKeySpec X509EncodedKeySpec)
    (java.time Instant ZoneOffset ZonedDateTime)
    (java.time.format DateTimeFormatter)
    (java.util Base64 Locale)))

(def tolerance-ms
  "How far a request's `Date` may sit from now, either way, before it is
  refused as a replay."
  300000)

(def ^:private algorithm "rsa-sha256")

(def ^:private read-headers ["(request-target)" "host" "date"])

(def ^:private write-headers
  ["(request-target)" "host" "date" "content-type" "digest" "content-length"])

(def ^:private ^DateTimeFormatter date-format
  "RFC 7231's HTTP-date, in English whatever the JVM's locale."
  (DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
                               Locale/ENGLISH))

(defn http-date
  [now-ms]
  (.format date-format
           (ZonedDateTime/ofInstant (Instant/ofEpochMilli now-ms)
                                    ZoneOffset/UTC)))

(defn- pem-body
  [pem]
  (.decode (Base64/getMimeDecoder)
           (str/replace pem #"-----[A-Z ]+-----" "")))

(defn- pem
  [label ^bytes der]
  (str "-----BEGIN "
       label
       "-----\n"
       (.encodeToString (Base64/getMimeEncoder) der)
       "\n-----END "
       label
       "-----\n"))

(defn private-key
  ^PrivateKey [pem]
  (.generatePrivate (KeyFactory/getInstance "RSA")
                    (PKCS8EncodedKeySpec. (pem-body pem))))

(defn public-key
  ^PublicKey [pem]
  (.generatePublic (KeyFactory/getInstance "RSA")
                   (X509EncodedKeySpec. (pem-body pem))))

(defn public-key-pem
  [^PublicKey k]
  (pem "PUBLIC KEY" (.getEncoded k)))

(defn key-pair
  []
  (let [pair (.generateKeyPair (doto (KeyPairGenerator/getInstance "RSA")
                                 (.initialize 2048)))]
    {:private-key (.getPrivate pair) :public-key (.getPublic pair)}))

(defn- ->bytes
  ^bytes [body]
  (cond
   (nil? body)
   (byte-array 0)

   (bytes? body)
   body

   :else
   (.getBytes (str body) StandardCharsets/UTF_8)))

(defn digest
  [body]
  (str "SHA-256="
       (.encodeToString (Base64/getEncoder)
                        (.digest (MessageDigest/getInstance "SHA-256")
                                 (->bytes body)))))

(defn- target
  [method path query]
  (str (str/lower-case (name method))
       " "
       path
       (when-not (str/blank? query) (str "?" query))))

(defn- signing-string
  [signed values]
  (str/join "\n"
            (map (fn [h] (str h ": " (get values h))) signed)))

(defn- sign
  [^PrivateKey k ^String s]
  (let [sig (doto (Signature/getInstance "SHA256withRSA")
              (.initSign k)
              (.update (.getBytes s StandardCharsets/UTF_8)))]
    (.encodeToString (Base64/getEncoder) (.sign sig))))

(defn- write?
  [method]
  (contains? #{:post :put :patch} (keyword (str/lower-case (name method)))))

(defn headers
  [{:keys [key-id private-key]}
   {:keys [method path query host body content-type]} now-ms]
  (let [date (http-date now-ms)
        body-bytes (->bytes body)
        write (write? method)
        content-type (or content-type "application/vnd.api+json")
        values (cond-> {"(request-target)" (target method path query)
                        "host" host
                        "date" date}
                       write
                       (assoc "content-type" content-type
                              "digest" (digest body-bytes)
                              "content-length" (str (alength body-bytes))))
        signed (if write write-headers read-headers)
        signature (sign private-key (signing-string signed values))]
    (cond-> {"Date" date
             "Authorization" (str "Signature keyId=\""
                                  key-id
                                  "\",algorithm=\""
                                  algorithm
                                  "\",headers=\""
                                  (str/join " " signed)
                                  "\",signature=\""
                                  signature
                                  "\"")}
            write
            (assoc "Content-Type" content-type
                   "Digest" (get values "digest")))))

(defn- params
  [header]
  (when (and header (str/starts-with? (str/trim header) "Signature "))
    (into {}
          (map (fn [[_ k v]] [k v]))
          (re-seq #"(\w+)=\"([^\"]*)\"" header))))

(defn- unverified
  [kind message]
  (error/unauthorized kind {:message message}))

(defn- parse-date
  [date]
  (error/try-nom :payment-webhook/invalid-date
                 "the request's date could not be read"
                 (.toEpochMilli (Instant/from
                                 (.parse (.withZone date-format ZoneOffset/UTC)
                                         date)))))

(defn- verifies?
  [^PublicKey k ^String s ^String signature]
  (let [sig (doto (Signature/getInstance "SHA256withRSA")
              (.initVerify k)
              (.update (.getBytes s StandardCharsets/UTF_8)))]
    (try (.verify sig (.decode (Base64/getDecoder) signature))
         (catch IllegalArgumentException _ false))))

(defn verify
  [public-keys {:keys [method path query headers body]} now-ms]
  (let [{:strs [keyId signature] :as ps} (params (get headers "authorization"))
        signed (some-> (get ps "headers")
                       (str/split #" "))
        date (get headers "date")
        sent-at (when date (parse-date date))
        body-bytes (->bytes body)
        k (get public-keys keyId)
        values (assoc headers
                      "(request-target)"
                      (target method path query))]
    (cond
     (or (nil? signature) (nil? signed))
     (unverified :payment-webhook/unsigned "the request carries no signature")

     (nil? k)
     (unverified :payment-webhook/unknown-key
                 "the request is signed with a key we do not hold")

     (not= algorithm (get ps "algorithm"))
     (unverified :payment-webhook/unsupported-algorithm
                 "the signature's algorithm is not accepted")

     (not (every? (set signed)
                  (if (write? method) write-headers read-headers)))
     (unverified :payment-webhook/unsigned
                 "the signature does not cover what it must")

     (error/anomaly? sent-at)
     sent-at

     (or (nil? sent-at) (> (abs (- now-ms sent-at)) tolerance-ms))
     (unverified :payment-webhook/stale "the request's date is too old")

     (and (write? method) (not= (digest body-bytes) (get headers "digest")))
     (unverified :payment-webhook/invalid-digest
                 "the request's body does not match its digest")

     (verifies? k (signing-string signed values) signature)
     {:verified true :key-id keyId}

     :else
     (unverified :payment-webhook/invalid-signature
                 "the request's signature does not verify"))))
