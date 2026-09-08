(ns com.repldriven.queenswood.api.oauth.handlers-test
  (:require
    [com.repldriven.queenswood.api.oauth.handlers :as SUT]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]

    [clojure.test :refer [deftest is testing]]))

(defn- provider-returning
  "An identity-provider whose grant exchange answers `result`. The
  protocol's methods are dash-prefixed; the interface's public
  wrappers call through to these."
  [result]
  (reify
   identity-provider/IdentityProvider
     (-create-service-account [_ _] result)
     (-revoke-service-account [_ _] result)
     (-rotate-secret [_ _] result)
     (-update-service-account-audience [_ _ _] result)
     (-exchange-client-credentials [_ _] result)
     (-verify-token [_ _ _] result)
     (-get-jwks [_] result)
     (-get-issuer [_] "https://identity.test/realms/queenswood")))

(defn- token
  [result]
  (SUT/token {:identity-provider (provider-returning result)
              :form-params {:grant_type "client_credentials"
                            :client_id "bnk.test"
                            :client_secret "secret"
                            :scope "queenswood-api-live"}}))

(deftest token-refused-grant-test
  (testing "an anomaly is 401 invalid_client, carrying its message"
    (let [response (token (error/reject :auth/invalid-client
                                        {:message "Unknown client_id"}))]
      (is (= 401 (:status response)))
      (is (= "invalid_client" (get-in response [:body :error])))
      (is (= "Unknown client_id"
             (get-in response [:body :error_description])))))
  (testing "a body carrying error is 401, carrying its error_description"
    (let [response (token {:error "unauthorized_client"
                           :error_description
                           "Invalid client or Invalid client credentials"})]
      (is (= 401 (:status response)))
      (is (= "invalid_client" (get-in response [:body :error])))
      (is (= "Invalid client or Invalid client credentials"
             (get-in response [:body :error_description])))))
  (testing "a body with no access_token is 401, with the fixed description"
    (let [response (token {})]
      (is (= 401 (:status response)))
      (is (= "invalid_client" (get-in response [:body :error])))
      (is (= "Authentication failed"
             (get-in response [:body :error_description]))))))

(deftest token-granted-test
  (testing "a body carrying access_token is 200 and passed through"
    (let [granted {:access_token "jwt.header.payload"
                   :token_type "Bearer"
                   :expires_in 3600
                   :scope "queenswood-api-live"}
          response (token granted)]
      (is (= 200 (:status response)))
      (is (= granted (:body response))))))
