(ns com.repldriven.queenswood.onfido-webhook.interface-test
  (:require
    [com.repldriven.queenswood.onfido-webhook.interface :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.nio.charset StandardCharsets)))

(def ^:private body
  (.getBytes "{\"payload\":{\"action\":\"workflow_run.completed\"}}"
             StandardCharsets/UTF_8))

(deftest verify-test
  (testing "a delivery signed with the webhook's token verifies"
    (is (= :verified (SUT/verify "token" (SUT/sign "token" body) body))))
  (testing "the signature is compared without regard to case"
    (is (= :verified
           (SUT/verify "token" (str/upper-case (SUT/sign "token" body)) body))))
  (testing "one signed with another token is refused"
    (is (= :idv-webhook/invalid-signature
           (error/kind (SUT/verify "token" (SUT/sign "other" body) body)))))
  (testing "one whose body changed is refused"
    (is (= :idv-webhook/invalid-signature
           (error/kind (SUT/verify "token"
                                   (SUT/sign "token" body)
                                   (.getBytes "{}" StandardCharsets/UTF_8))))))
  (testing "an unsigned one is refused"
    (is (= :idv-webhook/unsigned (error/kind (SUT/verify "token" nil body)))))
  (testing "nothing verifies without a token"
    (is (= :idv-webhook/no-secret
           (error/kind (SUT/verify "" (SUT/sign "token" body) body))))))
