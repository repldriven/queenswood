(ns com.repldriven.queenswood.zyphe-webhook.interface-test
  (:require
    [com.repldriven.queenswood.zyphe-webhook.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]])
  (:import
    (java.nio.charset StandardCharsets)))

(def ^:private secret
  "9f2b7c1d4e6a8035b1c9d2e4f6a80351d7e9b2c4f6a803517d9e2b4c6f8a0351")

(def ^:private body
  (.getBytes "{\"id\":\"evt-1\",\"type\":\"flow.completed\"}"
             StandardCharsets/UTF_8))

(def ^:private now-ms 1790000000000)

(def ^:private now-s (quot now-ms 1000))

(deftest sign-then-verify-test
  (testing "a delivery signed under the secret verifies"
    (let [header (SUT/sign secret now-s body)]
      (is (re-matches #"t=\d+,v0=[0-9a-f]{64}" header))
      (is (= {:timestamp now-s} (SUT/verify secret header body now-ms))))))

(deftest known-vector-test
  (testing
    "the digest is lowercase hex HMAC-SHA256, keyed by the hex-decoded
    secret, over <t>.<body> — computed independently of this code"
    (is (= (str
            "t=1700000000,v0="
            "c1da1b6c6b8e9da7f4bbb90f7cab0820f271ad19ccbf80c88479c4e14f37d1c6")
           (SUT/sign "00" 1700000000 (.getBytes "" StandardCharsets/UTF_8))))))

(deftest tampered-body-is-refused-test
  (let [header (SUT/sign secret now-s body)
        tampered (.getBytes "{\"id\":\"evt-2\"}" StandardCharsets/UTF_8)
        res (SUT/verify secret header tampered now-ms)]
    (is (error/anomaly? res))
    (is (= :idv-webhook/invalid-signature (error/kind res)))))

(deftest wrong-secret-is-refused-test
  (let [header (SUT/sign secret now-s body)
        res (SUT/verify (apply str (repeat 64 "a")) header body now-ms)]
    (is (= :idv-webhook/invalid-signature (error/kind res)))))

(deftest stale-delivery-is-refused-test
  (let [header (SUT/sign secret (- now-s 301) body)
        res (SUT/verify secret header body now-ms)]
    (is (= :idv-webhook/stale (error/kind res)))))

(deftest unsigned-delivery-is-refused-test
  (doseq [header [nil "" "v0=abc" "t=abc,v0=abc"]]
    (is (= :idv-webhook/unsigned
           (error/kind (SUT/verify secret header body now-ms)))
        (str "header " (pr-str header)))))

(deftest missing-secret-is-refused-test
  (is (= :idv-webhook/no-secret
         (error/kind
          (SUT/verify nil (SUT/sign secret now-s body) body now-ms)))))

(deftest non-hex-secret-is-an-anomaly-test
  (is (= :idv-webhook/secret (error/kind (SUT/sign "not-hex" now-s body)))))
