(ns com.repldriven.queenswood.modulr-webhook.signature
  (:require
    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str])
  (:import
    (java.net URLDecoder URLEncoder)
    (java.nio.charset StandardCharsets)
    (java.security MessageDigest SecureRandom)
    (java.time Instant ZoneOffset ZonedDateTime)
    (java.time.format DateTimeFormatter)
    (java.util Base64 HexFormat Locale)
    (javax.crypto Mac)
    (javax.crypto.spec SecretKeySpec)))

(def tolerance-ms
  "How far a request's `Date` may sit from now, either way, before it is
  refused as a replay."
  300000)

(def ^:private macs
  {"hmac-sha1" "HmacSHA1"
   "hmac-sha256" "HmacSHA256"
   "hmac-sha384" "HmacSHA384"
   "hmac-sha512" "HmacSHA512"})

(def ^:private signed-headers "date x-mod-nonce")

(def ^:private header-pattern
  #"^Signature keyId=\"([^\"]*)\",algorithm=\"([^\"]*)\",headers=\"([^\"]*)\",signature=\"([^\"]*)\"$")

(defn- random-hex
  [n]
  (let [bytes (byte-array n)]
    (.nextBytes (SecureRandom.) bytes)
    (.formatHex (HexFormat/of) bytes)))

(defn credentials
  [key-id secret]
  {:key-id (if (str/blank? key-id) (random-hex 16) key-id)
   :secret (if (str/blank? secret) (random-hex 16) secret)})

(defn nonce [] (random-hex 16))

(def ^:private ^DateTimeFormatter date-format
  "RFC 7231's HTTP-date, which pads the day to two digits where RFC 1123's
  formatter does not, in English whatever the JVM's locale."
  (DateTimeFormatter/ofPattern "EEE, dd MMM yyyy HH:mm:ss 'GMT'"
                               Locale/ENGLISH))

(defn http-date
  [now-ms]
  (.format date-format
           (ZonedDateTime/ofInstant (Instant/ofEpochMilli now-ms)
                                    ZoneOffset/UTC)))

(defn- signing-string
  [date nonce]
  (str "date: " date "\nx-mod-nonce: " nonce))

(defn- digest
  [secret algorithm date nonce]
  (let [mac-name (get macs algorithm)]
    (if-not mac-name
      (error/unauthorized :payment-webhook/unsupported-algorithm
                          {:message "the signature's algorithm is not accepted"
                           :algorithm algorithm})
      (let [mac (doto (Mac/getInstance mac-name)
                  (.init (SecretKeySpec. (.getBytes ^String secret
                                                    StandardCharsets/UTF_8)
                                         mac-name)))]
        (.encodeToString (Base64/getEncoder)
                         (.doFinal mac
                                   (.getBytes (signing-string date nonce)
                                              StandardCharsets/UTF_8)))))))

(defn authorization
  [{:keys [key-id secret algorithm]} date nonce]
  (let [algorithm (or algorithm "hmac-sha1")
        signature (digest secret algorithm date nonce)]
    (if (error/anomaly? signature)
      signature
      (str "Signature keyId=\""
           key-id
           "\",algorithm=\""
           algorithm
           "\",headers=\""
           signed-headers
           "\",signature=\""
           (URLEncoder/encode ^String signature
                              StandardCharsets/UTF_8)
           "\""))))

(defn headers
  [credentials nonce now-ms]
  (let [date (http-date now-ms)
        auth (authorization credentials date nonce)]
    (if (error/anomaly? auth)
      auth
      {"Authorization" auth "Date" date "x-mod-nonce" nonce})))

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

(defn- same?
  [^String a ^String b]
  (MessageDigest/isEqual (.getBytes a StandardCharsets/UTF_8)
                         (.getBytes b StandardCharsets/UTF_8)))

(defn verify
  [{:keys [key-id secret]} request-headers now-ms]
  (let [header (get request-headers "authorization")
        date (get request-headers "date")
        nonce (get request-headers "x-mod-nonce")
        [_ given-key algorithm signed given] (some->> header
                                                      str/trim
                                                      (re-matches
                                                       header-pattern))
        sent-at (when date (parse-date date))]
    (cond
     (str/blank? secret)
     (unverified :payment-webhook/no-key "no key to verify the signature with")

     (nil? given)
     (unverified :payment-webhook/unsigned "the request carries no signature")

     (or (str/blank? date) (str/blank? nonce) (not= signed-headers signed))
     (unverified :payment-webhook/unsigned
                 "the signature does not cover the date and nonce")

     (and key-id (not= key-id given-key))
     (unverified :payment-webhook/unknown-key
                 "the request is signed with a key we did not issue")

     (error/anomaly? sent-at)
     sent-at

     (> (abs (- now-ms sent-at)) tolerance-ms)
     (unverified :payment-webhook/stale "the request's date is too old")

     :else
     (let [expected (digest secret algorithm date nonce)]
       (cond
        (error/anomaly? expected)
        expected

        (same? expected
               (URLDecoder/decode ^String given StandardCharsets/UTF_8))
        {:verified true :nonce nonce}

        :else
        (unverified :payment-webhook/invalid-signature
                    "the request's signature does not verify"))))))
