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
  every authenticated human has a stable platform-identity record. A
  `Bank-Id` request header names the bank a call acts on, and a
  principal's roles carry the organisation level it holds there."
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

(def ^{:doc "The gate on every read of an organisation's own data."} org-viewer
  (keyword "org:viewer"))

(def ^{:doc "The gate on every write an organisation's own systems make."}
     org-developer
  (keyword "org:developer"))

(def ^{:doc "The gate on an organisation's people routes."} org-admin
  (keyword "org:admin"))

(def ^{:doc "The level only an owner holds."} org-owner (keyword "org:owner"))

(def ^{:doc "The organisation levels, lowest first."} org-levels
  [org-viewer org-developer org-admin org-owner])

(def
  ^{:doc
    "The levels each membership role carries: its own and every level
  below it."}
  role->levels
  {:role-viewer #{org-viewer}
   :role-developer #{org-viewer org-developer}
   :role-admin #{org-viewer org-developer org-admin}
   :role-owner (set org-levels)})

(def ^:private service-levels #{org-viewer org-developer})

(def
  ^{:private true
    :doc
    "The scopes that gate an organisation's own operations: the levels,
  and the `org` gate they replace."}
  org-scopes
  (conj (set org-levels) :org))

(defn- requested-bank-id
  "The bank the `Bank-Id` header names, or nil when it names none."
  [request]
  (some-> (get-in request [:headers "bank-id"])
          str/trim
          not-empty))

(defn- service-auth
  "Map a verified service-JWT claims map to the request auth context.
  The client_id (== bank-id by convention) appears as `:azp`, and the
  principal carries `org:viewer` and `org:developer`. A `Bank-Id`
  header naming another bank leaves it no bank and marks it
  `:bank-refused`. When the realm's role mapper has flagged the service
  account with `admin` (the `queenswood-admin` operator client), the
  principal picks up `:admin` and every level, and acts on the bank
  the header names, or none."
  [request claims]
  (let [realm-roles (->> (get-in claims [:realm_access :roles])
                         (map keyword)
                         (into #{}))
        is-admin? (contains? realm-roles :admin)
        requested (requested-bank-id request)
        refused? (boolean (and requested
                               (not is-admin?)
                               (not= requested (:azp claims))))]
    (util/assoc-some
     {:principal-type :service
      :principal-id (:azp claims)
      :roles (conj (into (if is-admin? (set org-levels) service-levels)
                         (disj realm-roles :org))
                   :org)
      :token-jti (:jti claims)}
     :bank-id
     (cond is-admin?
           requested
           refused?
           nil
           :else
           (:azp claims))
     :bank-refused
     (when refused? true))))

(defn- realm-access-roles
  "Project the JWT's `realm_access.roles` claim into a set of role
  keywords. Empty when the claim is absent (the SPA realm may not
  include a realm-roles mapper)."
  [claims]
  (into #{} (map keyword) (get-in claims [:realm_access :roles])))

(defn- resolve-membership
  "The active membership a call acts through: the one in the bank
  `requested` names, or with no header the person's only one. Nil when
  the header names a bank the person holds no active membership in, and
  when there is no header and the person holds several or none."
  [memberships requested]
  (if requested
    (some (fn [membership]
            (when (= requested (:bank-id membership)) membership))
          memberships)
    (when (= 1 (count memberships)) (first memberships))))

(defn- user-auth
  "Resolve a verified user-JWT into the principal sum-type. Upserts
  the User on every authenticated request — idempotent on the (iss,
  sub) pair, so first sign-in creates the row and subsequent sign-ins
  refresh mutable claims (email / name / avatar) only when they've
  changed. `:memberships` is the person's active memberships and
  `:membership` the one the call resolves to, whose bank is the
  principal's `:bank-id` and whose role's levels join `:user` in
  `:roles`. A `Bank-Id` header naming a bank the person holds no active
  membership in resolves none and marks the principal `:bank-refused`.
  An operator — the realm carries `admin` via `realm_access.roles` —
  holds `:admin` and every level, and acts on the bank the header
  names, or none.

  Returns the principal, or the first anomaly the store gave back. A
  store that cannot be reached is reported, not read as a user with no
  identity and no memberships."
  [request claims]
  (let [{:keys [record-db record-store]} request
        txn {:record-db record-db :record-store record-store}]
    (let-nom>
      [user (users/upsert-by-sub txn (claims/claims->user-claims claims))
       memberships (memberships/list-active-by-user txn (:user-id user))]
      (let [realm-roles (realm-access-roles claims)
            is-admin? (contains? realm-roles :admin)
            memberships (or memberships [])
            requested (requested-bank-id request)
            membership (resolve-membership memberships requested)
            levels (if is-admin?
                     (set org-levels)
                     (role->levels (:role membership)))]
        (util/assoc-some
         {:principal-type :user
          :principal-id (:user-id user)
          :issuer (:iss claims)
          :sub (:sub claims)
          :user user
          :claims claims
          :memberships memberships
          :roles (cond-> (into #{:user} levels)
                         is-admin?
                         (conj :admin)
                         (seq levels)
                         (conj :org))
          :token-jti (:jti claims)}
         :bank-id
         (if is-admin? requested (:bank-id membership))
         :membership
         membership
         :bank-refused
         (when (and requested (not is-admin?) (nil? membership)) true))))))

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
             (service-auth request claims)))))))})

(defn- bare-security?
  "True when `security` names a scheme and gives it no roles."
  [security]
  (boolean (some (fn [entry] (some empty? (vals entry))) security)))

(defn- operation-securities
  "Each compiled operation in `router` as its path and the OpenAPI
  security of its endpoint data: the method's own data meta-merged over
  the route's, which is what `authorize` enforces and the OpenAPI
  exporter documents."
  [router]
  (for [[path _ methods] (r/compiled-routes router)
        endpoint (vals methods)
        :when endpoint]
    [path (get-in endpoint [:data :openapi :security])]))

(defn- paths-where
  "The distinct paths in `router` with an operation whose security
  satisfies `pred`."
  [pred router]
  (into []
        (comp (filter (fn [[_ security]] (pred security)))
              (map first)
              (distinct))
        (operation-securities router)))

(defn bare-security-routes
  "The paths in `router` with an operation that names a security scheme
  but gives it no roles — a `{\"bearerAuth\" []}` entry. Such a gate
  demands a token and says nothing about what the token must carry, so
  `authorize` would have to guess. An operation whose `:security` is
  `[]` names no scheme at all and is public by design, so it is not
  reported."
  [router]
  (paths-where bare-security? router))

(defn- required-roles
  "The role set a route requires, read off its OpenAPI security. A
  route that names no scheme requires nothing and answers nil;
  otherwise the roles its scheme names, as keywords. A scheme that
  names no roles never reaches here — `bare-security-routes` refuses
  it while the router is being built."
  [security]
  (let [explicit (into #{} (comp (mapcat vals) cat) security)]
    (when (seq explicit) (into #{} (map keyword) explicit))))

(defn- stacked-levels?
  "True when `security` names more than one organisation level."
  [security]
  (< 1 (count (filter (set org-levels) (required-roles security)))))

(defn stacked-level-routes
  "The paths in `router` with an operation whose gate names more than
  one organisation level. Reitit concatenates a method's `:security`
  onto its route's unless the method's vector is marked `^:replace`, so
  a level declared under a method key of a gated route stacks on the
  route's level, and the operation admits the lower of the two."
  [router]
  (paths-where stacked-levels? router))

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

(defn- operation-security
  "The OpenAPI security of the matched operation: the endpoint data
  reitit compiled for the request's method."
  [request]
  (get-in request
          [:reitit.core/match :result (:request-method request) :data :openapi
           :security]))

(defn- bank-refused?
  "True when the principal's `Bank-Id` header named a bank it may not act
  on and the operation is an organisation's own. A route gated `user`,
  such as `/v1/me`, is not refused for a stale header."
  [request required]
  (and (get-in request [:auth :bank-refused])
       (boolean (some org-scopes required))))

(defn- bank-unnamed?
  "True when only organisation scopes gate the operation, the principal
  carries no bank, and it holds more than one active membership, so the
  header is what would name the bank."
  [request required]
  (and (every? org-scopes required)
       (nil? (get-in request [:auth :bank-id]))
       (< 1 (count (get-in request [:auth :memberships])))))

(defn- org-without-bank?
  "An organisation-gated route acts on the principal's bank, so a
  principal that reaches it through organisation scopes alone and
  carries no bank has nothing for the route to act on. A route an admin
  may call on any bank takes the bank from the path and declares `admin`
  alongside a level, so the intersection holds more than organisation
  scopes and this does not fire."
  [roles required request]
  (let [granted (set/intersection roles required)]
    (and (seq granted)
         (every? org-scopes granted)
         (nil? (get-in request [:auth :bank-id])))))

(def authorize
  {:name ::authorize
   :enter (fn [ctx]
            (let [request (:request ctx)
                  required (required-roles (operation-security request))]
              (if (nil? required)
                ctx
                (let [roles (get-in request [:auth :roles] #{})]
                  (cond
                   (empty? roles)
                   (sc/terminate ctx (unauthenticated-response))

                   (bank-refused? request required)
                   (sc/terminate
                    ctx
                    (forbidden-response
                     "The caller is not a member of this bank"))

                   (bank-unnamed? request required)
                   (sc/terminate
                    ctx
                    (forbidden-response
                     "Name the bank in the Bank-Id header"))

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
