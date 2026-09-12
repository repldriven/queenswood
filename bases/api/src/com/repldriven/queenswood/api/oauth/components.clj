(ns com.repldriven.queenswood.api.oauth.components
  (:require
    [com.repldriven.queenswood.api.oauth.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer
     [components-registry]]))

;; OAuth2 / OIDC payloads are snake_case at the wire boundary
;; because that's what RFC 6749 + RFC 8414 mandate. Internal Clojure
;; code keeps kebab-case keyword keys (Queenswood convention); the
;; handlers translate at the response boundary only.

(def TokenRequest
  [:map
   {:closed true :json-schema/example examples/TokenRequest}
   [:grant_type [:enum "client_credentials"]]
   [:client_id string?]
   [:client_secret string?]
   [:scope {:optional true} string?]])

(def TokenResponse
  [:map
   {:json-schema/example examples/TokenResponse}
   [:access_token string?]
   [:token_type [:enum "Bearer"]]
   [:expires_in pos-int?]
   [:scope {:optional true} string?]])

(def TokenError
  [:map
   {:json-schema/example examples/TokenError}
   [:error string?]
   [:error_description {:optional true} string?]])

(def Jwk
  [:map
   [:kid string?]
   [:kty string?]
   [:alg {:optional true} string?]
   [:use {:optional true} string?]
   [:n {:optional true} string?]
   [:e {:optional true} string?]])

(def JwksResponse
  [:map
   {:json-schema/example examples/JwksResponse}
   [:keys [:vector [:ref "Jwk"]]]])

(def DiscoveryDoc
  [:map
   {:json-schema/example examples/DiscoveryDoc}
   [:issuer string?]
   [:token_endpoint string?]
   [:jwks_uri string?]
   [:grant_types_supported [:vector string?]]
   [:response_types_supported [:vector string?]]
   [:id_token_signing_alg_values_supported [:vector string?]]
   [:token_endpoint_auth_methods_supported [:vector string?]]])

(def registry
  (components-registry [#'TokenRequest #'TokenResponse #'TokenError #'Jwk
                        #'JwksResponse #'DiscoveryDoc]))
