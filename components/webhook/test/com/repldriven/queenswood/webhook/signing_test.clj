(ns com.repldriven.queenswood.webhook.signing-test
  "Standard Webhooks signing (AC-16): the published vector reproduces,
  and a delivery inside a rotation window verifies under both the
  current and the previous secret."
  (:require
    [com.repldriven.queenswood.webhook.signing :as SUT]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (javax.crypto Mac)
    (javax.crypto.spec SecretKeySpec)
    (java.nio.charset StandardCharsets)
    (java.util Base64)))

(def ^:private vector-secret
  "The secret the Standard Webhooks specification's own example signs
  under. Its base64 payload carries no URL-alphabet character, so it
  reads the same either way."
  "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw")

(def ^:private vector-message-id "msg_p5jXN8AQM9LWM0D4loKWxJek")

(def ^:private vector-timestamp 1614265330)

(def ^:private vector-body "{\"test\": 2432232314}")

(def ^:private vector-signature
  "What the specification's example verifies against. Cross-checked
  against an independent HMAC-SHA256 over the same three inputs before
  it was written down here."
  "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=")

(defn- verify
  "A tenant's side of the check, written from the specification's
  pseudocode rather than from the code under test: re-sign the id, the
  timestamp and the body under `secret` and look for that signature
  among the ones the header carries."
  [secret message-id timestamp body header]
  (let [key-material (.decode (Base64/getDecoder)
                              (str/replace-first secret #"^whsec_" ""))
        mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. key-material "HmacSHA256")))
        signed (.getBytes (str message-id "." timestamp "." body)
                          StandardCharsets/UTF_8)
        expected (str "v1,"
                      (.encodeToString (Base64/getEncoder)
                                       (.doFinal mac signed)))]
    (contains? (set (str/split header #" ")) expected)))

(defn- body-bytes
  [s]
  (.getBytes ^String s StandardCharsets/UTF_8))

(deftest standard-webhooks-vector-test
  (testing "the published vector's signature reproduces exactly"
    (is (= vector-signature
           (SUT/sign vector-secret
                     vector-message-id
                     vector-timestamp
                     (body-bytes vector-body)))))
  (testing "the signed content is id, timestamp and body joined by dots"
    (is (= (str vector-message-id "." vector-timestamp "." vector-body)
           (String. ^bytes
                    (SUT/signed-content vector-message-id
                                        vector-timestamp
                                        (body-bytes vector-body))
                    StandardCharsets/UTF_8)))))

(deftest headers-test
  (let [now (* 1000 vector-timestamp)
        body (body-bytes vector-body)]
    (testing "an endpoint outside a rotation window carries one signature"
      (let [headers
            (SUT/headers {:secret vector-secret} vector-message-id body now)]
        (is (= vector-message-id (get headers SUT/id-header)))
        (is (= (str vector-timestamp) (get headers SUT/timestamp-header)))
        (is (= vector-signature (get headers SUT/signature-header)))))
    (testing "an expired previous secret is not signed under"
      (let [headers (SUT/headers {:secret vector-secret
                                  :previous-secret "whsec_cHJldmlvdXNrZXk="
                                  :previous-secret-expires-at (dec now)}
                                 vector-message-id
                                 body
                                 now)]
        (is (= vector-signature (get headers SUT/signature-header)))))
    (testing "inside a rotation window both secrets verify (AC-16)"
      (let [previous "whsec_cHJldmlvdXNrZXk="
            headers (SUT/headers {:secret vector-secret
                                  :previous-secret previous
                                  :previous-secret-expires-at (inc now)}
                                 vector-message-id
                                 body
                                 now)
            header (get headers SUT/signature-header)]
        (is (= 2 (count (str/split header #" "))))
        (is (verify vector-secret
                    vector-message-id
                    vector-timestamp
                    vector-body
                    header)
            "the current secret verifies")
        (is
         (verify previous vector-message-id vector-timestamp vector-body header)
         "the previous secret verifies")))))
