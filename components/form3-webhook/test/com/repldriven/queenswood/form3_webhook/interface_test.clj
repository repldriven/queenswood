(ns com.repldriven.queenswood.form3-webhook.interface-test
  (:require
    [com.repldriven.queenswood.form3-webhook.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(def ^:private now-ms 1790613486000)

(def ^:private pair (SUT/key-pair))

(def ^:private credentials
  {:key-id "7826c3cb-d6fd-41d0-b187-dc23ba928772"
   :private-key (:private-key pair)})

(def ^:private public-keys {(:key-id credentials) (:public-key pair)})

(def ^:private body "{\"data\":{\"id\":\"p1\"}}")

(defn- signed
  [request]
  (let [headers (SUT/headers credentials request now-ms)
        {:keys [host body]} request]
    (assoc request
           :headers
           (cond-> (merge {"host" host}
                          (update-keys headers str/lower-case))
                   body
                   (assoc "content-length"
                          (str (count (.getBytes ^String body
                                                 "UTF-8"))))))))

(def ^:private write
  {:method :post
   :path "/v1/transaction/payments"
   :host "localhost:8083"
   :body body})

(def ^:private read-request
  {:method :get
   :path "/v1/transaction/payments/p1"
   :query "filter[x]=1"
   :host "localhost:8083"})

(defn- refusal
  ([request] (refusal request now-ms))
  ([request at]
   (let [res (SUT/verify public-keys request at)]
     (is (error/unauthorized? res))
     (error/kind res))))

(deftest a-signed-request-verifies-test
  (testing "a write, over its body's digest"
    (let [request (signed write)]
      (is
       (str/includes?
        (get-in request [:headers "authorization"])
        "headers=\"(request-target) host date content-type digest content-length\""))
      (is (= {:verified true :key-id (:key-id credentials)}
             (SUT/verify public-keys request now-ms)))))
  (testing "a read, over its query"
    (is (:verified (SUT/verify public-keys (signed read-request) now-ms)))))

(deftest refusals-test
  (let [request (signed write)]
    (testing "no signature"
      (is (= :payment-webhook/unsigned
             (refusal (update request :headers dissoc "authorization")))))
    (testing "a key not held"
      (is (= :payment-webhook/unknown-key
             (refusal (update-in request
                                 [:headers "authorization"]
                                 str/replace
                                 (:key-id credentials)
                                 "another")))))
    (testing "a body other than the one digested"
      (is (= :payment-webhook/invalid-digest
             (refusal (assoc request :body "{}")))))
    (testing "another path"
      (is (= :payment-webhook/invalid-signature
             (refusal (assoc request :path "/v1/transaction/payments/p2")))))
    (testing "a date too far from now"
      (is (= :payment-webhook/stale
             (refusal request (+ now-ms SUT/tolerance-ms 1000)))))
    (testing "a write signed as a read"
      (is (= :payment-webhook/unsigned
             (refusal (assoc (signed read-request) :method :post)))))))

(deftest digest-test
  (is (= "SHA-256=47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU="
         (SUT/digest ""))))
