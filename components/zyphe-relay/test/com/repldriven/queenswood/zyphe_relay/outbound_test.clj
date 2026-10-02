(ns com.repldriven.queenswood.zyphe-relay.outbound-test
  "The outbound call's anomaly kinds are the provider-neutral `:idv/*`
  set, not the vendor's name, and the create-verification request
  carries what the webhook needs to correlate back. These drive the pure
  functions directly rather than faking the HTTP layer, because every
  way of doing that redefines a var for the whole JVM and this suite
  runs in parallel."
  (:require
    [com.repldriven.queenswood.zyphe-relay.outbound :as SUT]

    [com.repldriven.mono.error.interface :as error]

    [clojure.test :refer [deftest is testing]]))

(def ^:private provider-url "http://provider/sdk/flow/f/vr/create")

(defn- classifying
  [res]
  (#'SUT/classify provider-url res))

(defn- responding
  [status]
  (classifying {:status status :body "{}"}))

(def ^:private full
  {:id "full"
   :verifies ["identity" "liveness" "claimed-identity" "address"]
   :screens ["sanctions" "pep"]})

(def ^:private document-only
  {:id "document-only" :verifies ["identity" "liveness"] :screens []})

(def ^:private config
  {:zyphe-url "https://api.zyphe.com"
   :verify-url "https://verify.zyphe.com"
   :flows [full document-only]
   :sandbox "true"
   :adapter-url "https://adapter.example"
   :webhook-secret "9f2b7c1d"})

(deftest create-url-test
  (is (= (str "https://api.zyphe.com/sdk/flow/"
              "2d8285d7-f4ba-42df-ab3f-681d9870d37a/vr/create?sandbox=true")
         (SUT/create-url config "2d8285d7-f4ba-42df-ab3f-681d9870d37a"))))

(deftest select-flow-test
  (testing "the smallest flow covering the request"
    (is (= "document-only"
           (:id (SUT/select-flow [full document-only] ["identity"])))))
  (testing "a larger flow when the smaller does not cover"
    (is (= "full" (:id (SUT/select-flow [full document-only] ["pep"])))))
  (testing "none when nothing covers"
    (is (nil? (SUT/select-flow [document-only] ["address"])))))

(deftest uncovered-test
  (testing "what the declaration says that no flow establishes"
    (is (= #{"address" "pep"}
           (SUT/uncovered [document-only]
                          {:verifies ["identity" "address"]
                           :screens ["pep"]}))))
  (testing "nothing when the flows cover the declaration"
    (is (empty? (SUT/uncovered [full document-only]
                               {:verifies ["identity"] :screens ["pep"]})))))

(deftest hand-off-url-test
  (let [reply {:verificationRequest {:id "vr-1"}
               :zypheToken "tok"
               :zypheAccessSig "sig"
               :flowSlug "onboarding"}]
    (testing "a web hand-off returns the person to the tenant"
      (is (= (str "https://verify.zyphe.com/sandbox/flow/onboarding"
                  "?zypheVr=vr-1&zypheToken=tok&zypheAccessSig=sig"
                  "&zypheEmail=a%40example.com"
                  "&zypheHandoffBaseUrl=https%3A%2F%2Fapp.example%2Fback")
             (SUT/hand-off-url config
                               {:channel "web"
                                :email "a@example.com"
                                :return-url "https://app.example/back"}
                               reply))))
    (testing "a mobile hand-off asks for a full-screen layout"
      (is (re-find #"zypheFullscreen=true$"
                   (SUT/hand-off-url
                    config
                    {:channel "mobile" :return-url "app://back" :email nil}
                    reply))))
    (testing "production has no sandbox segment"
      (is (re-find #"^https://verify.zyphe.com/flow/onboarding\?"
                   (SUT/hand-off-url (assoc config :sandbox "false")
                                     {:channel "web" :return-url nil :email nil}
                                     reply))))))

(deftest verification-request-test
  (let [body (SUT/verification-request config
                                       {:bank-id "bnk.1"
                                        :verification-id "idv.1"
                                        :session-id "ses.1"
                                        :party-id "pty.1"
                                        :email "ada@example.com"
                                        :first-name "Ada"
                                        :last-name "Lovelace"})]
    (testing "the person is identified by party id and email, never by name"
      (is (= [{:type "EXTERNAL_ID" :externalId "pty.1"}] (:credentials body)))
      (is (= "ada@example.com" (:email body)))
      (is (not-any? #{"Ada" "Lovelace"} (tree-seq coll? seq body))))
    (testing "the bank, verification and session ids ride as customData"
      (is (= {:bankId "bnk.1" :verificationId "idv.1" :sessionId "ses.1"}
             (:customData body))))
    (testing "the session webhook points at the adapter, signed V2"
      (is (= {:url "https://adapter.example/webhooks/zyphe"
              :secret "9f2b7c1d"
              :payloadVersion "V2"}
             (:webhook body))))))

(deftest unreachable-provider-is-unavailable-test
  (let [anomaly (classifying (error/fail :http-client/request
                                         {:message "HTTP request failed"}))]
    (is (= :idv/unavailable (error/kind anomaly)))
    (is (some? (:cause (error/payload anomaly))))))

(deftest upstream-5xx-is-unavailable-test
  (doseq [status [500 502 503]]
    (is (= :idv/unavailable (error/kind (responding status)))
        (str "status " status))))

(deftest upstream-429-is-rate-limited-test
  (is (= :idv/rate-limited (error/kind (responding 429)))))

(deftest remaining-4xx-keeps-the-call-site-name-test
  (doseq [status [400 401 403 409]]
    (is (= :idv/http (error/kind (responding status))) (str "status " status))))

(deftest success-passes-the-response-through-test
  (let [res (responding 200)]
    (is (not (error/anomaly? res)))
    (is (= 200 (:status res)))))

(deftest no-kind-names-the-vendor-test
  (doseq [status [429 503 400]]
    (is (= "idv" (namespace (error/kind (responding status)))))))
