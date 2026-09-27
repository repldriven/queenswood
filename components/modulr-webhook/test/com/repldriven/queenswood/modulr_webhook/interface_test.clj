(ns com.repldriven.queenswood.modulr-webhook.interface-test
  (:require
    [com.repldriven.queenswood.modulr-webhook.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(def ^:private documented
  "Modulr's worked example from its authentication guide."
  {:key-id "57502612d1bb2c0001000025fd53850cd9a94861507a5f7cca236882"
   :secret "NzAwZmIwMGQ0YTJiNDhkMzZjYzc3YjQ5OGQyYWMzOTI="
   :algorithm "hmac-sha1"})

(def ^:private documented-ms "Mon, 25 Jul 2016 16:36:07 GMT" 1469464567000)

(def ^:private documented-nonce "28154b2-9c62b93cc22a-24c9e2-5536d7d")

(defn- lower-case-keys
  [m]
  (update-keys m str/lower-case))

(deftest documented-vector-test
  (testing "the headers match Modulr's worked example"
    (is (= {"Date" "Mon, 25 Jul 2016 16:36:07 GMT"
            "x-mod-nonce" documented-nonce
            "Authorization"
            (str "Signature keyId=\"" (:key-id documented)
                 "\",algorithm=\"hmac-sha1\",headers=\"date x-mod-nonce\","
                 "signature=\"WBMr%2FYdhysbmiIEkdTrf2hP7SfA%3D\"")}
           (SUT/headers documented documented-nonce documented-ms)))))

(deftest day-of-month-is-padded-test
  (is (= "Thu, 05 Feb 2026 08:54:13 GMT"
         (get (SUT/headers documented "n" 1770281653000) "Date"))))

(deftest sign-then-verify-test
  (doseq [algorithm ["hmac-sha1" "hmac-sha256" "hmac-sha512"]]
    (testing algorithm
      (let [credentials (assoc documented :algorithm algorithm)
            headers (SUT/headers credentials "n-1" documented-ms)]
        (is (= {:verified true :nonce "n-1"}
               (SUT/verify credentials
                           (lower-case-keys headers)
                           documented-ms)))))))

(defn- refusal
  [credentials headers now-ms]
  (let [res (SUT/verify credentials headers now-ms)]
    (is (error/unauthorized? res))
    (error/kind res)))

(deftest refusals-test
  (let [headers (lower-case-keys
                 (SUT/headers documented documented-nonce documented-ms))]
    (testing "no signature"
      (is
       (= :payment-webhook/unsigned
          (refusal documented (dissoc headers "authorization") documented-ms))))
    (testing "a nonce other than the one signed"
      (is (= :payment-webhook/invalid-signature
             (refusal documented
                      (assoc headers "x-mod-nonce" "other")
                      documented-ms))))
    (testing "another secret"
      (is (= :payment-webhook/invalid-signature
             (refusal (assoc documented :secret "another")
                      headers
                      documented-ms))))
    (testing "another key id"
      (is (= :payment-webhook/unknown-key
             (refusal (assoc documented :key-id "another")
                      headers
                      documented-ms))))
    (testing "any key id when the credentials name none"
      (is (:verified
           (SUT/verify (dissoc documented :key-id) headers documented-ms))))
    (testing "a date too far from now"
      (is (= :payment-webhook/stale
             (refusal documented
                      headers
                      (+ documented-ms SUT/tolerance-ms 1000)))))
    (testing "no secret to verify with"
      (is (= :payment-webhook/no-key
             (refusal (dissoc documented :secret) headers documented-ms))))))
