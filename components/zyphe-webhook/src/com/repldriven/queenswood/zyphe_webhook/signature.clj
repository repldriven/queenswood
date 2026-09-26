(ns com.repldriven.queenswood.zyphe-webhook.signature
  (:require
    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str])
  (:import
    (java.nio.charset StandardCharsets)
    (java.security MessageDigest)
    (java.util HexFormat)
    (javax.crypto Mac)
    (javax.crypto.spec SecretKeySpec)))

(def tolerance-ms
  "How far a delivery's timestamp may sit from now, either way, before
  it is refused as a replay: five minutes, Zyphe's recommendation."
  300000)

(def ^:private hmac-algorithm "HmacSHA256")

(def ^:private header-pattern #"^t=(\d+),v0=([a-fA-F0-9]+)$")

(defn- signed-content
  [timestamp ^bytes body]
  (let [prefix (.getBytes (str timestamp ".") StandardCharsets/UTF_8)
        out (byte-array (+ (alength prefix) (alength body)))]
    (System/arraycopy prefix 0 out 0 (alength prefix))
    (System/arraycopy body 0 out (alength prefix) (alength body))
    out))

(defn- digest
  [secret timestamp body]
  (error/try-nom
   :idv-webhook/secret
   "the webhook secret is not hexadecimal"
   (let [mac (doto (Mac/getInstance hmac-algorithm)
               (.init (SecretKeySpec. (.parseHex (HexFormat/of) secret)
                                      hmac-algorithm)))]
     (.formatHex (HexFormat/of)
                 (.doFinal mac (signed-content timestamp body))))))

(defn sign
  [secret timestamp body]
  (let [d (digest secret timestamp body)]
    (if (error/anomaly? d)
      d
      (str "t=" timestamp ",v0=" d))))

(defn- unverified
  [kind message]
  (error/unauthorized kind {:message message}))

(defn verify
  [secret header ^bytes body now]
  (let [[_ timestamp given] (some->> header
                                     str/trim
                                     (re-matches header-pattern))
        seconds (some-> timestamp
                        parse-long)]
    (cond
     (str/blank? secret)
     (unverified :idv-webhook/no-secret "the adapter holds no webhook secret")

     (nil? seconds)
     (unverified :idv-webhook/unsigned "the delivery carries no signature")

     (> (abs (- now (* 1000 seconds))) tolerance-ms)
     (unverified :idv-webhook/stale "the delivery's timestamp is too old")

     :else
     (let [expected (digest secret timestamp body)]
       (cond
        (error/anomaly? expected)
        expected

        (MessageDigest/isEqual (.getBytes (str/lower-case given)
                                          StandardCharsets/UTF_8)
                               (.getBytes ^String expected
                                          StandardCharsets/UTF_8))
        {:timestamp seconds}

        :else
        (unverified :idv-webhook/invalid-signature
                    "the delivery's signature does not verify"))))))
