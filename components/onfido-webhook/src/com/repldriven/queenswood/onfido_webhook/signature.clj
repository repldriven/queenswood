(ns com.repldriven.queenswood.onfido-webhook.signature
  (:require
    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str])
  (:import
    (java.nio.charset StandardCharsets)
    (java.security MessageDigest)
    (java.util HexFormat)
    (javax.crypto Mac)
    (javax.crypto.spec SecretKeySpec)))

(def ^:private hmac-algorithm "HmacSHA256")

(defn sign
  [token ^bytes body]
  (error/try-nom
   :idv-webhook/secret
   "the webhook token cannot key a signature"
   (let [mac (doto (Mac/getInstance hmac-algorithm)
               (.init (SecretKeySpec. (.getBytes ^String token
                                                 StandardCharsets/UTF_8)
                                      hmac-algorithm)))]
     (.formatHex (HexFormat/of) (.doFinal mac body)))))

(defn- unverified
  [kind message]
  (error/unauthorized kind {:message message}))

(defn verify
  [token header ^bytes body]
  (cond
   (str/blank? token)
   (unverified :idv-webhook/no-secret "the adapter holds no webhook token")

   (str/blank? header)
   (unverified :idv-webhook/unsigned "the delivery carries no signature")

   :else
   (let [expected (sign token body)]
     (cond
      (error/anomaly? expected)
      expected

      (MessageDigest/isEqual (.getBytes (str/lower-case (str/trim header))
                                        StandardCharsets/UTF_8)
                             (.getBytes ^String expected
                                        StandardCharsets/UTF_8))
      :verified

      :else
      (unverified :idv-webhook/invalid-signature
                  "the delivery's signature does not verify")))))
