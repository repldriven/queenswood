(ns com.repldriven.queenswood.clearbank-webhook.interface-test
  (:require
    [com.repldriven.queenswood.clearbank-webhook.interface :as SUT]

    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer [with-test-system]]

    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]])
  (:import
    (java.nio.file Files)
    (java.nio.file.attribute FileAttribute)
    (java.security Key)
    (java.util Base64)))

(defn- write-pem
  [dir file-name label ^Key k]
  (let [f (io/file dir file-name)]
    (spit f
          (str "-----BEGIN "
               label
               "-----\n"
               (.encodeToString (Base64/getMimeEncoder) (.getEncoded k))
               "\n-----END "
               label
               "-----\n"))
    (str f)))

(deftest key-pair-reads-each-half-from-a-file-test
  (testing "the signer's private half and the verifier's public half agree"
    (let [dir (str (Files/createTempDirectory "clearbank-key"
                                              (make-array FileAttribute 0)))
          pair (SUT/key-pair)
          private-file
          (write-pem dir "private.pem" "PRIVATE KEY" (:private-key pair))
          public-file
          (write-pem dir "public.pem" "PUBLIC KEY" (:public-key pair))]
      (with-test-system
       [sys
        ["classpath:clearbank-webhook/key-pair-test.yml"
         (fn [defs]
           (-> defs
               (assoc-in [:system/defs :clearbank-webhook :signer
                          :system/config :private-key-file]
                         private-file)
               (assoc-in [:system/defs :clearbank-webhook :verifier
                          :system/config :public-key-file]
                         public-file)))]]
       (let [signer (system/instance sys [:clearbank-webhook :signer])
             verifier (system/instance sys [:clearbank-webhook :verifier])
             body "{\"amount\":100}"]
         (is (nil? (:public-key signer)) "the signer holds its private half")
         (is (nil? (:private-key verifier))
             "the verifier holds the public half")
         (is (= {:verified true}
                (SUT/verify (:public-key verifier)
                            (SUT/sign (:private-key signer) body)
                            body)))))))
  (testing "neither file named generates a pair"
    (with-test-system [sys "classpath:clearbank-webhook/key-pair-test.yml"]
                      (let [k (system/instance sys
                                               [:clearbank-webhook :signer])]
                        (is (some? (:private-key k)))
                        (is (some? (:public-key k)))))))
