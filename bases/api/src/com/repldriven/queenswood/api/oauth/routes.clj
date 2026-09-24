(ns com.repldriven.queenswood.api.oauth.routes
  (:require
    [com.repldriven.queenswood.api.oauth.handlers :as handlers]))

;; All three routes are unauthenticated by design: the token endpoint
;; carries credentials in its own body (the OAuth2 contract), and
;; JWKS + discovery are public metadata. We park them at the API root
;; rather than under `/v1` because the OAuth2 + OIDC specs expect
;; well-known and token endpoints at the issuer / authorization-server
;; root.

(defn- oauth-error
  [description examples]
  {:description description
   :content {"application/json"
             {:schema [:ref "TokenError"]
              :examples (into {}
                              (map (fn [example]
                                     [example
                                      {"$ref" (str "#/components/examples/"
                                                   example)}]))
                              examples)}}})

(def routes
  [["/oauth"
    {:openapi {:tags ["OAuth"] :security []}}
    ["/token"
     {:post {:summary "Exchange client_credentials for a JWT"
             :openapi
             {:operationId "TokenExchange"
              :description
              (str "Only the `client_credentials` grant is supported, with "
                   "`client_id` and `client_secret` in the form body. "
                   "Returns 400 for another grant type or a missing field, "
                   "and 401 when the identity provider rejects the "
                   "credentials.")}
             :parameters {:form [:ref "TokenRequest"]}
             :responses {200 {:description "The access token."
                              :body [:ref "TokenResponse"]}
                         400 (oauth-error (str "An OAuth 2.0 error: an "
                                               "unsupported grant type or "
                                               "a missing field.")
                                          ["UnsupportedGrantType"
                                           "InvalidTokenRequest"])
                         401 (oauth-error (str "An OAuth 2.0 error: the "
                                               "credentials were rejected.")
                                          ["InvalidClient"])}
             :handler handlers/token}}]
    ["/jwks"
     {:get
      {:summary "Retrieve the JWK Set"
       :openapi {:operationId "RetrieveJwks"
                 :description
                 (str "The public keys that sign access tokens, for verifying "
                      "a token without calling the API. Returns 502 when the "
                      "identity provider is unreachable.")}
       :responses {200 {:description "The JWK Set." :body [:ref "JwksResponse"]}
                   502 (oauth-error (str "An OAuth 2.0 error: the "
                                         "signing keys could not be "
                                         "fetched.")
                                    ["SigningKeysUnavailable"])}
       :handler handlers/jwks}}]]
   ["/.well-known/openid-configuration"
    {:openapi {:tags ["OAuth"] :security []}
     :get {:summary "Retrieve the OIDC discovery document"
           :openapi {:operationId "RetrieveDiscoveryDocument"
                     :description
                     (str "Names the issuer every access token carries, the "
                          "token and JWK Set endpoints, and the one supported "
                          "grant, `client_credentials` with "
                          "`client_secret_post`.")}
           :responses {200 {:description "The discovery document."
                            :body [:ref "DiscoveryDoc"]}}
           :handler handlers/discovery}}]])
