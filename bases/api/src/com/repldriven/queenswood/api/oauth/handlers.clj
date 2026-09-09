(ns com.repldriven.queenswood.api.oauth.handlers
  "RFC 6749 token endpoint + RFC 7517 JWKS endpoint + RFC 8414
  discovery, layered on top of the `identity-provider` substrate.
  The handlers speak snake_case at the wire boundary (OAuth2 spec)
  and let the substrate decide whether the request hits a real
  Keycloak realm or the in-memory local impl."
  (:require
    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]))

(defn- external-base-url
  "Derive the public URL the caller used to reach the API. Honours
  `X-Forwarded-Proto` / `X-Forwarded-Host` if present (Gateway
  terminating TLS in front of the pod), otherwise falls back to the
  request scheme + Host header."
  [request]
  (let [headers (:headers request)
        scheme (or (get headers "x-forwarded-proto")
                   (some-> (:scheme request)
                           name)
                   "https")
        host (or (get headers "x-forwarded-host")
                 (get headers "host"))]
    (str scheme "://" host)))

(defn- oauth-error
  [status code description]
  {:status status
   :headers {"content-type" "application/json"}
   :body
   (cond-> {:error code} description (assoc :error_description description))})

(defn token
  "RFC 6749 §4.4 — client_credentials grant. Proxies the request to
  the realm's token endpoint via the configured identity-provider."
  [request]
  (let [{:keys [identity-provider parameters form-params]} request
        {:keys [form]} parameters
        params (or form form-params)
        {:keys [grant_type client_id client_secret scope]} params]
    (cond
     (not= "client_credentials" grant_type)
     (oauth-error 400
                  "unsupported_grant_type"
                  "Only client_credentials is supported")

     (or (empty? client_id) (empty? client_secret))
     (oauth-error 400
                  "invalid_request"
                  "Missing client_id or client_secret")

     :else
     (let [result (identity-provider/exchange-client-credentials
                   identity-provider
                   {:client-id client_id
                    :client-secret client_secret
                    :scope scope})]
       ;; 200 only for a body carrying access_token. A realm refuses a
       ;; grant with an `error` body, which passed through here and then
       ;; failed to coerce against TokenResponse — a 500 for an ordinary
       ;; authentication failure.
       (cond
        (error/anomaly? result)
        (oauth-error 401
                     "invalid_client"
                     (or (:message (error/payload result))
                         "Authentication failed"))

        (or (:error result) (not (:access_token result)))
        (oauth-error 401
                     "invalid_client"
                     (or (:error_description result) "Authentication failed"))

        :else
        {:status 200 :body result})))))

(defn jwks
  "RFC 7517 — return the realm's signing keys so consumers can verify
  tokens offline."
  [request]
  (let [{:keys [identity-provider]} request
        result (identity-provider/get-jwks identity-provider)]
    (if (error/anomaly? result)
      {:status 502
       :headers {"content-type" "application/json"}
       :body {:error "server_error"
              :error_description "Failed to fetch signing keys"}}
      {:status 200 :body (select-keys result [:keys])})))

(defn discovery
  "RFC 8414 / OpenID Connect Discovery — minimal subset describing
  the proxy's token + JWKS endpoints. `issuer` is the realm's own
  iss URL because that's what ends up in every JWT's `iss` claim;
  strict OIDC clients should fetch discovery from there too."
  [request]
  (let [{:keys [identity-provider]} request
        issuer (identity-provider/get-issuer identity-provider)
        base (external-base-url request)]
    {:status 200
     :body {:issuer issuer
            :token_endpoint (str base "/oauth/token")
            :jwks_uri (str base "/oauth/jwks")
            :grant_types_supported ["client_credentials"]
            :response_types_supported ["token"]
            :id_token_signing_alg_values_supported ["RS256"]
            :token_endpoint_auth_methods_supported ["client_secret_post"]}}))
