(ns com.repldriven.queenswood.api.oauth.routes
  (:require
    [com.repldriven.queenswood.api.oauth.handlers :as handlers]))

;; All three routes are unauthenticated by design: the token endpoint
;; carries credentials in its own body (the OAuth2 contract), and
;; JWKS + discovery are public metadata. We park them at the API root
;; rather than under `/v1` because the OAuth2 + OIDC specs expect
;; well-known and token endpoints at the issuer / authorization-server
;; root.

(def routes
  [["/oauth"
    {:openapi {:tags ["OAuth"] :security []}}
    ["/token"
     {:post {:summary "Exchange client_credentials for a JWT"
             :openapi {:operationId "TokenExchange"}
             :parameters {:form [:ref "TokenRequest"]}
             :responses {200 {:body [:ref "TokenResponse"]}
                         400 {:body [:ref "TokenError"]}
                         401 {:body [:ref "TokenError"]}}
             :handler handlers/token}}]
    ["/jwks"
     {:get {:summary "Return the realm's JWK Set so consumers can verify tokens"
            :openapi {:operationId "GetJwks"}
            :responses {200 {:body [:ref "JwksResponse"]}}
            :handler handlers/jwks}}]]
   ["/.well-known/openid-configuration"
    {:openapi {:tags ["OAuth"] :security []}
     :get {:summary "OIDC discovery document"
           :openapi {:operationId "GetDiscoveryDocument"}
           :responses {200 {:body [:ref "DiscoveryDoc"]}}
           :handler handlers/discovery}}]])
