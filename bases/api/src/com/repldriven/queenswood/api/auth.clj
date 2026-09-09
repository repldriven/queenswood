(ns com.repldriven.queenswood.api.auth
  "Two-path JWT authentication. (1) A Keycloak-issued service JWT
  minted by a tenant's service-account client (`client_credentials`
  flow); principal type `:service`. The `queenswood-admin` service
  account carries the `admin` realm role, which grants `:admin` —
  giving operators a Keycloak-minted admin bearer in place of a
  static env-var key. (2) A Keycloak-issued user JWT minted by either
  the `queenswood-console` SPA against the `queenswood` realm (org
  admins/members) or the `queenswood-app` SPA against the
  `queenswood-ops` realm (Queenswood operators); principal type
  `:user`. JWT verification dispatches across multiple
  `identity-provider` instances keyed by the unverified `iss` claim,
  then the verified `azp` claim discriminates user vs service. The
  user path always upserts a `bank-user` row on first sign-in so
  every authenticated human has a stable platform-identity record."
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.api.shared.claims :as claims]

    [com.repldriven.queenswood.membership.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as util]

    [reitit.core :as r]
    [sieppari.context :as sc]

    [clojure.set :as set]
    [clojure.string :as str])
  (:import
    (java.util Base64)))

(defn- extract-bearer
  [request]
  (some-> (get-in request [:headers "authorization"])
          (str/split #" " 2)
          (as-> parts (when (= "Bearer" (first parts)) (second parts)))))

(defn- service-auth
  "Map a verified service-JWT claims map to the request auth context.
  The client_id (== bank-id by convention) appears as `:azp`.
  When the realm's role mapper has flagged the service account with
  `admin` (the `queenswood-admin` operator client), the principal
  picks up `:admin` alongside `:org`. Admins carry no implicit
  `:bank-id` — admin routes operate platform-wide or take the bank
  from the request path."
  [claims]
  (let [realm-roles (->> (get-in claims [:realm_access :roles])
                         (map keyword)
                         (into #{}))
        is-admin? (contains? realm-roles :admin)]
    {:principal-type :service
     :principal-id (:azp claims)
     :bank-id (when-not is-admin? (:azp claims))
     :roles (into #{:org} realm-roles)
     :token-jti (:jti claims)}))

(defn- realm-access-roles
  "Project the JWT's `realm_access.roles` claim into a set of role
  keywords. Empty when the claim is absent (the SPA realm may not
  include a realm-roles mapper)."
  [claims]
  (into #{} (map keyword) (get-in claims [:realm_access :roles])))

(defn- user-auth
  "Resolve a verified user-JWT into the principal sum-type. Upserts
  the User on every authenticated request — idempotent on the (iss,
  sub) pair, so first sign-in creates the row and subsequent sign-ins
  refresh mutable claims (email / name / avatar) only when they've
  changed. Roles always include `:user`; `:admin` is added when the
  realm carries it via `realm_access.roles`; `:org` is added when the
  user has at least one membership OR is admin (so existing org-
  scoped routes accept ops JWTs). The principal's `:bank-id` is the
  first membership's bank, or nil — admins carry no implicit bank-id.

  Returns the principal, or the first anomaly the store gave back. A
  store that cannot be reached is reported, not read as a user with no
  identity and no memberships."
  [request claims]
  (let [{:keys [record-db record-store]} request
        txn {:record-db record-db :record-store record-store}]
    (let-nom>
      [user (users/upsert-by-sub txn (claims/claims->user-claims claims))
       memberships (memberships/list-by-user txn (:user-id user))]
      (let [realm-roles (realm-access-roles claims)
            is-admin? (contains? realm-roles :admin)
            memberships (or memberships [])
            primary (first memberships)]
        (util/assoc-some
         {:principal-type :user
          :principal-id (:user-id user)
          :issuer (:iss claims)
          :sub (:sub claims)
          :user user
          :claims claims
          :memberships memberships
          :roles (cond-> #{:user}
                         is-admin?
                         (conj :admin :org)
                         (seq memberships)
                         (conj :org))
          :token-jti (:jti claims)}
         :bank-id
         (:bank-id primary))))))

(defn- decode-unverified-payload
  "Best-effort base64url-decode of a JWT's middle segment into the
  payload claims map. Returns nil on any failure — callers should
  treat that the same as an unverifiable token."
  [^String jwt-string]
  (try
    (let [parts (str/split jwt-string #"\." 3)
          payload-bytes (.decode (Base64/getUrlDecoder)
                                 ^String (second parts))
          payload-str (String. payload-bytes "UTF-8")
          parsed (json/read-str payload-str :key-fn keyword)]
      (when (map? parsed) parsed))
    (catch Exception _ nil)))

(defn- unverified-issuer
  "Pull the `iss` claim out of the JWT payload WITHOUT signature
  verification — used only to pick which identity-provider should be
  asked to verify. The verifier still rejects the token if iss
  doesn't match its expected issuer, so a forged iss can't gain
  access to a realm whose JWKS it can't satisfy."
  [jwt-string]
  (:iss (decode-unverified-payload jwt-string)))

(defn- pick-provider
  "Find the identity-provider instance whose configured issuer matches
  the JWT's (unverified) iss claim. Returns nil when none match."
  [providers iss]
  (some (fn [p] (when (= iss (identity-provider/get-issuer p)) p))
        providers))

(def authenticate
  {:name ::authenticate
   :enter
   (fn [ctx]
     (let [request (:request ctx)
           token (extract-bearer request)
           {:keys [user-client-ids identity-providers expected-audiences]}
           request]
       (if (nil? token)
         ctx
         (let [iss (unverified-issuer token)
               provider (pick-provider identity-providers iss)
               claims (when provider
                        (identity-provider/verify-token
                         provider
                         token
                         {:expected-audiences (set expected-audiences)}))]
           (cond
            (nil? provider)
            (do (log/warn "JWT verification rejected: unknown issuer"
                          (pr-str iss))
                ctx)

            (not (map? claims))
            (do (log/warn "JWT verification rejected:"
                          (:message (error/payload claims))
                          "iss:" (pr-str iss))
                ctx)

            (contains? (set user-client-ids) (:azp claims))
            (let [auth (user-auth request claims)]
              (if (error/anomaly? auth)
                (do (log/warn "User sign-in failed:"
                              (:message (error/payload auth))
                              "iss:" (pr-str (:iss claims))
                              "sub:" (pr-str (:sub claims)))
                    (sc/terminate ctx (errors/anomaly->response auth)))
                (assoc-in ctx [:request :auth] auth)))

            :else
            (assoc-in ctx
             [:request :auth]
             (service-auth claims)))))))})

(defn- bare-security?
  "True when `security` names a scheme and gives it no roles."
  [security]
  (boolean (some (fn [entry] (some empty? (vals entry))) security)))

(defn- method-securities
  "Every OpenAPI security declaration written under one of a route's
  method keys, rather than on the route itself."
  [data]
  (keep (fn [v] (when (map? v) (get-in v [:openapi :security])))
        (vals data)))

(defn bare-security-routes
  "The paths in `router` that name a security scheme but give it no
  roles — a `{\"bearerAuth\" []}` entry. Such a route has no gate
  anyone can read: it demands a token and says nothing about what the
  token must carry, so `authorize` would have to guess. A route whose
  `:security` is `[]` names no scheme at all and is public by design,
  so it is not reported."
  [router]
  (into []
        (comp (filter (fn [[_ data]]
                        (bare-security? (get-in data [:openapi :security]))))
              (map first))
        (r/routes router)))

(defn method-security-routes
  "The paths in `router` that declare `:security` under a method key
  instead of on the route. `authorize` reads the match's route-level
  data, and reitit merges method data into the compiled endpoints and
  never into that map, so a gate written under `:post` is advertised
  by the generated OpenAPI and enforced by nobody. A route's gate is
  written in exactly one place, so any such declaration is reported —
  including an empty one, which reads as a method-level override of a
  route-level gate and is not honoured either."
  [router]
  (into []
        (comp (filter (fn [[_ data]] (seq (method-securities data))))
              (map first))
        (r/routes router)))

(defn- required-roles
  "The role set a route requires, read off its OpenAPI security. A
  route that names no scheme requires nothing and answers nil;
  otherwise the roles its scheme names, as keywords. A scheme that
  names no roles never reaches here — `bare-security-routes` refuses
  it while the router is being built."
  [security]
  (let [explicit (into #{} (comp (mapcat vals) cat) security)]
    (when (seq explicit) (into #{} (map keyword) explicit))))

(defn unauthenticated-response
  "The 401 an unauthenticated caller receives. Public so a handler that
  refuses a caller on its own terms answers in exactly this shape:
  `errors/anomaly->response` derives `type` from an anomaly kind and so
  writes a leading colon, which this shape does not carry."
  ([] (unauthenticated-response nil))
  ([detail]
   {:status 401
    :headers {"content-type" "application/json"}
    :body {:title "UNAUTHORIZED"
           :type "auth/unauthenticated"
           :status 401
           :detail (or detail "Missing or invalid token")}}))

(defn forbidden-response
  "The 403 a caller whose roles miss the route's receives. Public for
  the same reason as `unauthenticated-response`: a handler enforcing a
  tenant boundary of its own must be indistinguishable from this
  interceptor."
  ([] (forbidden-response nil))
  ([detail]
   {:status 403
    :headers {"content-type" "application/json"}
    :body {:title "FORBIDDEN"
           :type "auth/forbidden"
           :status 403
           :detail (or detail "Insufficient privileges")}}))

(defn- org-without-bank?
  "An `org`-gated route acts on the principal's bank, so a principal
  that reaches it through `:org` alone and carries no bank has nothing
  for the route to act on. A route an admin may call on any bank takes
  the bank from the path and declares `admin` alongside `org`, so the
  intersection is wider than `#{:org}` and this does not fire."
  [roles required request]
  (and (= #{:org} (set/intersection roles required))
       (nil? (get-in request [:auth :bank-id]))))

(def authorize
  {:name ::authorize
   :enter (fn [ctx]
            (let [request (:request ctx)
                  security
                  (get-in request [:reitit.core/match :data :openapi :security])
                  required (required-roles security)]
              (if (nil? required)
                ctx
                (let [roles (get-in request [:auth :roles] #{})]
                  (cond
                   (empty? roles)
                   (sc/terminate ctx (unauthenticated-response))

                   (empty? (set/intersection roles required))
                   (sc/terminate ctx (forbidden-response))

                   (org-without-bank? roles required request)
                   (sc/terminate
                    ctx
                    (forbidden-response
                     (str "This route acts on the caller's bank and the"
                          " token names none")))

                   :else
                   ctx)))))})
