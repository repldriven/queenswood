(ns com.repldriven.queenswood.api.oauth.examples
  (:require
    [com.repldriven.queenswood.api-schema.interface :refer
     [examples-registry]]))

(def TokenRequest
  {:grant_type "client_credentials"
   :client_id "org.01H8MA8DQ7T6Q3J5N5R3VKMRZX"
   :client_secret "k4kZQqK0Zd8QXJ4..."
   :scope "queenswood-api-live"})

(def TokenResponse
  {:access_token "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJhM..."
   :token_type "Bearer"
   :expires_in 3600
   :scope "queenswood-api-live"})

(def TokenError
  {:error "invalid_client" :error_description "Authentication failed"})

(def JwksResponse
  {:keys
   [{:kid "a3-1" :kty "RSA" :alg "RS256" :use "sig" :n "wPj-8w..." :e "AQAB"}]})

(def DiscoveryDoc
  {:issuer "https://keycloak.queenswood.repldriven.com/realms/queenswood"
   :token_endpoint "https://queenswood.repldriven.com/oauth/token"
   :jwks_uri "https://queenswood.repldriven.com/oauth/jwks"
   :grant_types_supported ["client_credentials"]
   :response_types_supported ["token"]
   :id_token_signing_alg_values_supported ["RS256"]
   :token_endpoint_auth_methods_supported ["client_secret_post"]})

(def registry (examples-registry []))
