(ns com.repldriven.queenswood.api.auth
  "The principal behind a verified token, and the bank it acts on. The
  credential, its verification against the realm that issued it and the
  scopes its claims grant are the server brick's: `server/credential`,
  `server/authenticate-with-provider`, `server/claims->scopes` and
  `server/require-scopes`. Queenswood's part begins at `:auth-claims`. The
  verified `azp` claim tells a user JWT, minted by the `queenswood-console`
  SPA against the `queenswood` realm or the `queenswood-app` SPA against
  the `queenswood-ops` realm, from a service JWT minted by a tenant's
  service-account client, of which `queenswood-admin` carries the `admin`
  realm role and so `:admin`. The user path upserts a `bank-user` row on
  first sign-in so every authenticated human has a stable platform-identity
  record. A `Bank-Id` request header names the bank a call acts on, and a
  principal's roles carry the organisation level it holds there, joining
  `:auth-scopes` for `server/require-scopes` to enforce. `claims->principal`
  resolves `:auth` only where nothing before it has, as the server brick's
  component interceptors leave a key a request already carries."
  (:require
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.api.shared.claims :as claims]

    [com.repldriven.queenswood.membership-query.interface :as memberships]
    [com.repldriven.queenswood.user.interface :as users]

    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as util]

    [sieppari.context :as sc]

    [clojure.set :as set]
    [clojure.string :as str]))

(def org-viewer "Reads an organisation's data." (keyword "org:viewer"))

(def org-developer "Writes an organisation's data." (keyword "org:developer"))

(def org-admin "Manages an organisation's people." (keyword "org:admin"))

(def org-owner "Held by an owner alone." (keyword "org:owner"))

(def org-levels "Lowest first." [org-viewer org-developer org-admin org-owner])

(def role->levels
  "The levels a membership role carries: its own and every one below it."
  {:role-viewer #{org-viewer}
   :role-developer #{org-viewer org-developer}
   :role-admin #{org-viewer org-developer org-admin}
   :role-owner (set org-levels)})

(def ^:private service-levels #{org-viewer org-developer})

(def ^:private org-scopes "The levels as a set." (set org-levels))

(def scopes
  "Every scope a gate may name: the levels, `admin` and `user`."
  (into #{"admin" "user"} (map name) org-levels))

(def exclusive-scopes
  "A gate names one level, so a method's stacked on its route's is refused."
  [(into #{} (map name) org-levels)])

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
      :roles (into (if is-admin? (set org-levels) service-levels)
                   (disj realm-roles :org))
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
                         (conj :admin))
          :token-jti (:jti claims)}
         :bank-id
         (if is-admin? requested (:bank-id membership))
         :membership
         membership
         :bank-refused
         (when (and requested (not is-admin?) (nil? membership)) true))))))

(def claims->principal
  {:name ::claims->principal
   :enter (fn [ctx]
            (let [{:keys [request]} ctx
                  {:keys [auth-claims auth user-client-ids]} request]
              (if (or (nil? auth-claims) (some? auth))
                ctx
                (let [auth (if (contains? (set user-client-ids)
                                          (:azp auth-claims))
                             (user-auth request auth-claims)
                             (service-auth request auth-claims))]
                  (if (error/anomaly? auth)
                    (do (log/warn "User sign-in failed:" (:message
                                                          (error/payload auth))
                                  "iss:" (pr-str (:iss auth-claims))
                                  "sub:" (pr-str (:sub auth-claims)))
                        (sc/terminate ctx (errors/anomaly->response auth)))
                    (-> ctx
                        (assoc-in [:request :auth] auth)
                        (update-in [:request :auth-scopes]
                                   (fnil into #{})
                                   (map name)
                                   (:roles auth))))))))})

(defn- required-roles
  "The role set a route requires, read off its OpenAPI security. A
  route that names no scheme requires nothing and answers nil;
  otherwise the roles its scheme names, as keywords. A scheme that
  names no roles never reaches here — `server/require-scopes` refuses
  it while the router is being built."
  [security]
  (let [explicit (into #{} (comp (mapcat vals) cat) security)]
    (when (seq explicit) (into #{} (map keyword) explicit))))

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

(defn- bank-absent?
  "True when the principal reaches the operation through organisation
  scopes alone and carries no bank, so an organisation-gated route has
  nothing to act on. A route an admin may call on any bank takes the bank
  from the path and declares `admin` as a second requirement object beside
  a level, so the scopes required hold more than organisation scopes and
  this does not fire."
  [roles required request]
  (let [granted (set/intersection roles required)]
    (and (seq granted)
         (every? org-scopes granted)
         (nil? (get-in request [:auth :bank-id])))))

(def require-bank
  {:name ::require-bank
   :compile
   (fn [data _]
     (when-some [required (required-roles (get-in data [:openapi :security]))]
       {:enter (fn [ctx]
                 (let [{:keys [request]} ctx
                       roles (get-in request [:auth :roles] #{})]
                   (cond
                    (bank-refused? request required)
                    (sc/terminate ctx
                                  (errors/forbidden-response
                                   "The caller is not a member of this bank"))

                    (bank-unnamed? request required)
                    (sc/terminate ctx
                                  (errors/forbidden-response
                                   "Name the bank in the Bank-Id header"))

                    (bank-absent? roles required request)
                    (sc/terminate
                     ctx
                     (errors/forbidden-response
                      (str "This route acts on the caller's bank and the"
                           " token names none")))

                    :else
                    ctx)))}))})
