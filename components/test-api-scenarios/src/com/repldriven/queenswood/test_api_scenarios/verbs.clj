(ns com.repldriven.queenswood.test-api-scenarios.verbs
  (:require
    [com.repldriven.queenswood.test-api-scenarios.await :as await]
    [com.repldriven.queenswood.test-api-scenarios.refs :as refs]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.utility.interface :as utility]

    [buddy.sign.jwt :as jwt]
    [matcher-combinators.matchers :as m]
    [matcher-combinators.standalone :as standalone]

    [clojure.string :as str]
    [clojure.test :refer [is]]
    [clojure.walk :as walk])
  (:import
    (java.security KeyPair)
    (java.util Base64)
    (javax.crypto Mac)
    (javax.crypto.spec SecretKeySpec)))

(def ^:private matcher-constructors
  "EDN `:m/<name>` markers → matcher-combinators constructors.

  Maps that appear in expected position embed by default (sub-map
  semantics); vectors must match length and order; scalars compare
  with `=`. Markers below switch to richer matchers."
  {:m/equals (fn [v] (m/equals v))
   :m/embeds (fn [v] (m/embeds v))
   :m/nested-equals (fn [v] (m/nested-equals v))
   :m/regex (fn [p] (m/regex (re-pattern p)))
   :m/in-any-order (fn [coll] (m/in-any-order coll))
   :m/set-equals (fn [coll] (m/set-equals coll))
   :m/set-embeds (fn [coll] (m/set-embeds coll))
   :m/seq-of (fn [mm] (m/seq-of mm))
   :m/prefix (fn [coll] (m/prefix coll))
   :m/absent (fn [] m/absent)
   :m/any-of (fn [& ms] (apply m/any-of ms))
   :m/all-of (fn [& ms] (apply m/all-of ms))
   :m/mismatch (fn [mm] (m/mismatch mm))
   :m/within-delta (fn [d v] (m/within-delta d v))
   :m/any (fn [] (m/pred any?))
   :m/non-empty (fn [] (m/pred seq))})

(defn- matcher-marker?
  [x]
  (and (vector? x)
       (keyword? (first x))
       (= "m" (namespace (first x)))))

(defn- expand-marker
  [[tag & args]]
  (if-let [ctor (get matcher-constructors tag)]
    (apply ctor args)
    ;; nosemgrep: no-raw-throw
    (throw (ex-info "Unknown matcher marker"
                    {:tag tag
                     :args args
                     :known (sort (keys matcher-constructors))}))))

(defn- expand-matchers
  [form]
  (walk/postwalk
   (fn [x] (if (matcher-marker? x) (expand-marker x) x))
   form))

(defn- capture
  [ctx as value]
  (assoc-in ctx (into [:captures] (if (vector? as) as [as])) value))

(defn- resolve-auth
  [{:keys [admin-token captures]} auth]
  (cond
   (nil? auth)
   nil

   (= :admin auth)
   admin-token

   ;; A keyword references a previously-captured token (minted by
   ;; `:auth/mint-token` and stored via `:as`).
   (keyword? auth)
   (get captures auth)

   :else
   auth))

(defn- substitute-path
  [path path-params]
  (reduce-kv (fn [p k v] (.replace ^String p (str "{" (name k) "}") (str v)))
             path
             (or path-params {})))

(defn- header-name
  [k]
  (if (keyword? k) (name k) k))

(defn- merge-headers
  [base extra]
  (reduce-kv (fn [m k v] (assoc m (header-name k) v)) base (or extra {})))

(defn- absolute-url?
  [s]
  (and (string? s)
       (or (.startsWith ^String s "http://")
           (.startsWith ^String s "https://"))))

(defn- resolve-url
  [base-url url path path-params]
  (cond
   (absolute-url? url)
   url

   (some? url)
   (str base-url url)

   :else
   (str base-url (substitute-path path path-params))))

(defn- form-urlencode
  [params]
  (->> params
       (map (fn [[k v]]
              (str (java.net.URLEncoder/encode (name k) "UTF-8")
                   "="
                   (java.net.URLEncoder/encode (str v) "UTF-8"))))
       (str/join "&")))

(defn- base-url-for
  "The root URL a request goes to: the bank API's, or the payment
  provider's simulator's when the step names `:base :payment-simulator`."
  [{:keys [base-url payment-simulator-url]} base]
  (case base
    :payment-simulator payment-simulator-url
    base-url))

(defn- for-run
  "The request as the run it belongs to sends it: a bank created naming
  no providers names the run's, and an idempotency key carries the
  run's suffix, so a second run in one boot replays nothing of the
  first."
  [{:keys [providers key-suffix]} {:keys [method path body] :as request}]
  (cond-> request
          (and providers
               (= :post method)
               (= "/v1/banks" path)
               (map? body)
               (not (contains? body :providers)))
          (assoc-in [:body :providers] providers)

          key-suffix
          (update :headers
                  (fn [headers]
                    (into {}
                          (map (fn [[k v]]
                                 (if (= "idempotency-key"
                                        (str/lower-case (header-name k)))
                                   [k (str v "-" key-suffix)]
                                   [k v])))
                          headers)))))

(defn- build-request
  [ctx request]
  (let [{:keys [base method url path path-params query-params body form auth
                headers]}
        (for-run ctx request)
        token (resolve-auth ctx auth)
        base-headers (cond-> {}
                             body
                             (assoc "Content-Type" "application/json")

                             form
                             (assoc "Content-Type"
                                    "application/x-www-form-urlencoded")

                             token
                             (assoc "Authorization" (str "Bearer " token)))]
    (cond-> {:method method
             :url (resolve-url (base-url-for ctx base) url path path-params)
             :headers (merge-headers base-headers headers)}
            query-params
            (assoc :query-params query-params)

            body
            (assoc :body (json/write-str body))

            form
            (assoc :body (form-urlencode form)))))

(defn- assert-match
  [expected actual label]
  (let [matcher (expand-matchers expected)
        ok? (standalone/match? matcher actual)]
    (is ok?
        (when-not ok?
          (str label
               " mismatch:\n"
               (with-out-str
                 (standalone/print! (standalone/match matcher actual))))))))

(def ^:private replay-header
  "The header the idempotency cache marks a replayed response with."
  :idempotent-replayed)

(defn- replayed?
  [response]
  (= "true" (get-in response [:headers replay-header])))

(defn- send-once
  [ctx request]
  (let [res (http/request (build-request ctx request))]
    {:status (:status res)
     :body (http/res->edn res)
     :headers (:headers res)}))

(defn- audience-scope
  "The audience a bank's client credentials are exchanged for. A
  scenario spells the bank's status as a keyword in its own `:for`
  map and a create response spells it as a string, so both reach the
  live audience."
  [status]
  (if (#{:live "live"} status)
    "queenswood-api-live"
    "queenswood-api-test"))

(defn- mint-token
  "Exchange a bank's client credentials at `/oauth/token` for a
  bank-scoped bearer token, as `{:token :response}`. `:token` is nil
  when the exchange is refused, and `:response` is what says why."
  [{:keys [base-url]} {:keys [client-id client-secret status scope]}]
  (let [res (http/request {:method :post
                           :url (str base-url "/oauth/token")
                           :headers {"content-type"
                                     "application/x-www-form-urlencoded"}
                           :body (str "grant_type=client_credentials"
                                      "&client_id=" client-id
                                      "&client_secret=" client-secret
                                      "&scope=" (or scope
                                                    (audience-scope status)))})
        body (http/res->edn res)]
    {:token (:access_token body)
     :response {:status (:status res) :body body}}))

(def ^:private create-bank-request
  "The request a bank is created by. Its 201 is what `track-bank`
  reacts to."
  {:method :post :path "/v1/banks"})

(defn- created-bank?
  [request response]
  (and (= create-bank-request (select-keys request [:method :path]))
       (= 201 (:status response))))

(defn- track-bank
  "Record a bank a step just created under `:banks`, with a bearer
  token minted from its own client credentials. The standing
  assertions take their `bank-id` from the token rather than a path,
  so a bank the run holds no token for cannot be read at all: one
  whose credentials are absent or will not exchange is logged and
  listed under `:skipped-banks` instead of dropping out unremarked."
  [ctx {:keys [bank-id client-id client-secret status]}]
  (let [{:keys [token response]} (when (and client-id client-secret)
                                   (mint-token ctx
                                               {:client-id client-id
                                                :client-secret client-secret
                                                :status status}))]
    (cond
     (nil? bank-id)
     ctx

     token
     (assoc-in ctx [:banks bank-id] {:bank-id bank-id :token token})

     :else
     (do (log/warn "api scenario skipping a bank it cannot mint a token for"
                   {:bank-id bank-id
                    :bank-status status
                    :token-status (:status response)})
         (update ctx
                 :skipped-banks
                 (fnil conj [])
                 {:bank-id bank-id
                  :reason (if client-id
                            :token-exchange-refused
                            :no-client-credentials)})))))

(def ^:private verification-return-url "https://app.example.test/verified")

(defn- person-document
  "What the person's own document says: the names read off the party as
  the tenant registered it, and a date of birth the platform never
  holds."
  [ctx auth party-id]
  (let [{:keys [body]} (send-once ctx
                                  {:method :get
                                   :path (str "/v1/parties/" party-id)
                                   :query-params {"embed[legal-name]"
                                                  "true"}
                                   :auth auth})
        words (str/split (:legal-name body) #" ")]
    {:givenNames (str/join " " (butlast words))
     :familyName (last words)
     :dateOfBirth "1970-01-01"}))

(defn- open-session
  "Open a verification session for `party-id` straight after its
  creation, as a tenant's app would."
  [ctx auth party-id channel email]
  (send-once ctx
             {:method :post
              :path (str "/v1/parties/" party-id "/verification-sessions")
              :auth auth
              :headers {"Idempotency-Key" (str "ik-verify-" (utility/uuidv7))}
              :body (cond-> {:channel (or channel "web")
                             :return-url verification-return-url}
                            email
                            (assoc :email email))}))

(defn- ready-session
  [{:keys [await-timeout-ms] :as ctx} auth party-id session-id]
  (let [{:keys [done? value]}
        (await/until {:timeout-ms await-timeout-ms}
                     (fn []
                       (:body (send-once ctx
                                         {:method :get
                                          :path (str "/v1/parties/" party-id
                                                     "/verification-sessions/"
                                                     session-id)
                                          :auth auth})))
                     (fn [body] (= "ready" (:status body))))]
    (when done? value)))

(def ^:private zyphe-run #"[?&]zypheVr=([^&]+)")

(def ^:private onfido-link #"^(https?://[^/]+).*/l/([^/?#]+)")

(defn- decision-url
  "The decision route of the simulator a hand-off `url` points at: a
  Zyphe run's, named by its `zypheVr`, or an Onfido workflow run's,
  named by its link's path. Nil for a hand-off neither simulator
  issued."
  [zyphe-simulator-url url]
  (if-let [vr (some->> url
                       (re-find zyphe-run)
                       second)]
    (str zyphe-simulator-url "/simulator/verification-requests/" vr "/decision")
    (when-let [[_ origin run] (some->> url
                                       (re-find onfido-link))]
      (str origin "/simulator/workflow-runs/" run "/decision"))))

(defn- verify-party
  "Stand in for the tenant's app and the person: open a session for
  `party`, wait for its hand-off, and submit `outcome` (default a
  document that matches) with what `document` says (default the party's
  own details) to the decision route of the simulator the hand-off
  points at — the body its hosted page posts. Returns the ready session,
  or nil having failed an assertion saying where it stopped."
  [{:keys [zyphe-simulator-url] :as ctx}
   {:keys [party auth outcome document channel email]}]
  (let [opened (open-session ctx
                             auth
                             party
                             channel
                             ;; Unique per verification: the Zyphe
                             ;; simulator resumes a pending run for the
                             ;; same email, so a shared address hands one
                             ;; scenario's run to another running beside
                             ;; it.
                             (or email
                                 (str "person+"
                                      (utility/uuidv7)
                                      "@example.test")))
        session-id (get-in opened [:body :session-id])]
    (is (= 202 (:status opened))
        (str "opening a verification session: " (pr-str opened)))
    (when session-id
      (let [ready (ready-session ctx auth party session-id)
            decide (decision-url zyphe-simulator-url
                                 (get-in ready [:hand-off :url]))]
        (is (some? decide) (str "the session never became ready: " party))
        (when decide
          (let [res (http/request
                     {:method :post
                      :url decide
                      :headers {"Content-Type" "application/json"}
                      :body (json/write-str
                             (merge {:outcome (or outcome "match")}
                                    (or document
                                        (person-document ctx auth party))))})]
            (is (= 200 (:status res))
                (str "deciding the verification: " (pr-str res)))
            ready))))))

(defmulti dispatch
  "Scenario step dispatch. `:api/*` methods drive the bank API over
  HTTP; `:assert/*` methods check the previous response.

  `:mail/await-invitation` waits for the `:nth` email (default the
  first) to `:to` whose link names `:invitation-id`, and captures its
  `:invitation-id`, `:token` and `:subject` under `:as`.

  `:api/race` sends one request `:count` times at once and asserts the
  idempotency invariant over the answers rather than their timing: see
  its own method.

  `:idv/verify` verifies a `:party` as the tenant's app and the person
  would: it opens a verification session and submits a document that
  matches the party through the identity-provider simulator, or another
  `:outcome` or `:document`, capturing the ready session under `:as`. A
  person party a step creates is not verified unless a step says so.

  `:webhook/open-receiver` captures under `:as` an `:address` on the
  rig's receiver that no other step uses. `:webhook/await-delivery`
  waits for `:count` requests (default one) to that `:address` whose
  body matches `:where`, asserts no more than that arrived, checks each
  one's signature under `:secret` where the step names it, and captures
  each as `{:headers :body}`, the body parsed.

  `:assert/equals` asserts `:actual` is exactly `:expected`, each
  resolved from the captures, with no matcher between them."
  (fn [_ctx command] (:command command)))

(def ^:private write-methods #{:post :put :patch :delete})

(def ^:private lost-reply-prefix
  "The key prefix the lost-reply seam in the test tree loses the reply to."
  "ik-lost-reply-")

(defn- idempotency-header
  [headers]
  (some (fn [k] (when (= "idempotency-key" (str/lower-case (header-name k))) k))
        (keys headers)))

(defn- with-idempotency-key
  "The request with the key it is sent under: its own, or one generated
  for the run and the step when it names none and the step does not
  say `:idempotency-key false`. A `:fault :lost-reply` step's key
  carries the prefix the lost-reply seam acts on."
  [{:keys [run-id counter]} {:keys [fault] :as step}
   {:keys [method] :as request}]
  (let [header (idempotency-header (:headers request))
        generate? (and (write-methods method)
                       (nil? header)
                       (not (false? (:idempotency-key step))))
        lose (fn [k]
               (if (str/starts-with? k lost-reply-prefix)
                 k
                 (str lost-reply-prefix k)))]
    (cond-> request
            generate?
            (assoc-in [:headers :idempotency-key]
             (str "ik-gen-" run-id "-" counter))

            (= :lost-reply fault)
            (update-in [:headers (or header :idempotency-key)] lose))))

(defn- capture-bank-token
  "Capture the token `track-bank` minted for the bank this step created."
  [ctx token-as response]
  (let [token (get-in ctx [:banks (get-in response [:body :bank-id]) :token])]
    (is (some? token)
        (str "no token was minted for the bank this step created: "
             (pr-str (:body response))))
    (capture ctx token-as token)))

(defmethod dispatch :api/request
  [{:keys [captures] :as ctx} {:keys [request as token-as] :as step}]
  (let [resolved
        (with-idempotency-key ctx step (refs/resolve-all captures request))
        response (send-once ctx resolved)
        created-bank (created-bank? resolved response)
        ctx' (cond-> (assoc ctx :last-response response)
                     as
                     (capture as (:body response))

                     created-bank
                     (track-bank (:body response))

                     (and created-bank token-as)
                     (capture-bank-token token-as response))
        ctx'' (if-let [expect (:assert step)]
                (dispatch ctx' {:command :assert/response :assert expect})
                ctx')]
    ctx''))

(defmethod dispatch :idv/verify
  [{:keys [captures] :as ctx} {:keys [as] :as step}]
  (let [session (verify-party ctx (refs/resolve-all captures step))]
    (cond-> ctx
            as
            (capture as session))))

(defmethod dispatch :api/race
  [{:keys [captures] :as ctx} {:keys [request as] n :count :as step}]
  (let [{:keys [status fresh]} (refs/resolve-all captures (:assert step))
        resolved (refs/resolve-all captures request)
        ;; Every future must exist before any of them is deref'd.
        ;; `repeatedly` is lazy, so deref'ing straight off the seq
        ;; would start and finish one request before creating the
        ;; next, and the race would serialise. It would still pass:
        ;; one fresh response and the rest replays is exactly what
        ;; serialised requests give, so this vector is the only thing
        ;; making the step race at all.
        in-flight (into [] (repeatedly n #(future (send-once ctx resolved))))
        responses (mapv deref in-flight)
        [fresh-responses others] (reduce (fn [[f o] response]
                                           (if (and (= status
                                                       (:status response))
                                                    (not (replayed? response)))
                                             [(conj f response) o]
                                             [f (conj o response)]))
                                         [[] []]
                                         responses)
        original (first fresh-responses)]
    (is (= fresh (count fresh-responses))
        (str ":api/race expected "
             fresh
             " response(s) with status "
             status
             " and no replay header,"
             " got " (count fresh-responses)
             "; statuses: " (pr-str (mapv :status responses))))
    ;; Whether a loser sees the winner still in flight or already
    ;; committed is the scheduler's business, so both answers pass. What
    ;; is invariant is that no loser ran the handler again.
    (doseq [response others]
      (is (or (and (= 409 (:status response))
                   (= "mono/idempotent-request-in-flight"
                      (get-in response [:body :type])))
              (and (replayed? response)
                   (= status (:status response))
                   (= (:body original) (:body response))))
          (str ":api/race response was neither a 409 in-flight nor an"
               " exact replay of the fresh response: "
               (pr-str response))))
    ;; Captured fresh-first. The vector's own order is meaningless --
    ;; deref'd in creation order, not completion order -- so a scenario
    ;; reading `[:ref alias 0 :body ...]` wants the one that ran the
    ;; handler, not whichever future was built first.
    (cond-> (assoc ctx :last-response (or original (first responses)))
            as
            (capture as (into (vec fresh-responses) others)))))

(defmethod dispatch :wait
  [ctx {:keys [duration-ms]}]
  (Thread/sleep ^long duration-ms)
  ctx)

(defmethod dispatch :api/poll
  [{:keys [captures await-timeout-ms] :as ctx}
   {:keys [request until timeout-ms interval-ms as]}]
  (let [resolved-request (refs/resolve-all captures request)
        until-matcher (expand-matchers (refs/resolve-all captures until))
        timeout (or timeout-ms await-timeout-ms)
        {:keys [done? value]} (await/until
                               {:timeout-ms timeout :interval-ms interval-ms}
                               (fn [] (send-once ctx resolved-request))
                               (fn [response]
                                 (standalone/match? until-matcher response)))]
    (if done?
      (cond-> (assoc ctx :last-response value)
              as
              (capture as (:body value)))
      (do (is false
              (str "poll timed out after "
                   (or timeout await/default-timeout-ms)
                   "ms waiting for response"
                   " to match\n  expected: " (pr-str until)
                   "\n  last actual: " (pr-str value)))
          ctx))))

;; An invitation email's link, `/#/invitations/<id>?token=<token>`.
(def ^:private invitation-link
  #"/#/invitations/([^?\s]+)\?token=([A-Za-z0-9_-]+)")

(defn- mail-get
  [mail-url path]
  (http/res->edn (http/request {:method :get :url (str mail-url path)})))

(defn- invitation-emails
  "The invitation links emailed to `to` for `invitation-id`, oldest
  first, each with the message's subject."
  [mail-url to invitation-id]
  (let [query (java.net.URLEncoder/encode (str "to:\"" to "\"") "UTF-8")
        found (mail-get mail-url (str "/api/v1/search?query=" query))]
    (into []
          (keep (fn [{:keys [ID Subject]}]
                  (let [{:keys [Text]} (mail-get mail-url
                                                 (str "/api/v1/message/" ID))
                        [_ id token] (some->> Text
                                              (re-find invitation-link))]
                    (when (= invitation-id id)
                      {:invitation-id id :token token :subject Subject}))))
          (reverse (:messages found)))))

(defmethod dispatch :mail/await-invitation
  [{:keys [captures mail-url await-timeout-ms] :as ctx} step]
  (let [{:keys [to invitation-id nth timeout-ms as]} (refs/resolve-all captures
                                                                       step)
        n (or nth 1)
        {:keys [done? value]}
        (await/until {:timeout-ms (or timeout-ms await-timeout-ms)}
                     (fn [] (invitation-emails mail-url to invitation-id))
                     (fn [emails] (<= n (count emails))))]
    (if done?
      (cond-> ctx
              as
              (capture as (get value (dec n))))
      (do (is false
              (str ":mail/await-invitation timed out waiting for email " n
                   " to " to
                   " for " invitation-id
                   "; found " (count value)))
          ctx))))

(defmethod dispatch :assert/response
  [{:keys [captures last-response] :as ctx} {expectation :assert}]
  (let [{:keys [status body headers problem]} (refs/resolve-all captures
                                                                expectation)
        actual-body (:body last-response)]
    (when status
      (is (= status (:status last-response))
          (str "expected status " status
               " got " (:status last-response)
               "; body: " (pr-str actual-body))))
    (when body (assert-match body actual-body "body"))
    (when headers (assert-match headers (:headers last-response) "headers"))
    (when problem
      (assert-match (merge {:type [:m/any] :title [:m/any]} problem)
                    actual-body
                    "problem-details")))
  ctx)

(defmethod dispatch :auth/mint-token
  [{:keys [captures] :as ctx} {:keys [for as]}]
  (let [{:keys [token response]} (mint-token ctx
                                             (refs/resolve-all captures for))]
    (when-not token
      (is false
          (str ":auth/mint-token failed to exchange credentials at /oauth/token"
               " — status: " (:status response)
               " body: " (pr-str (:body response)))))
    (cond-> ctx
            as
            (capture as token))))

(def ^:private token-path "/protocol/openid-connect/token")

(defmethod dispatch :auth/mint-user-token
  [{:keys [captures token-endpoints] :as ctx}
   {:keys [realm client-id username password scope as]}]
  (let [{:keys [realm client-id username password]} (refs/resolve-all
                                                     captures
                                                     {:realm realm
                                                      :client-id client-id
                                                      :username username
                                                      :password password})
        endpoint (get token-endpoints realm)]
    (if-not endpoint
      (do (is false
              (str ":auth/mint-user-token knows no token endpoint for realm "
                   (pr-str realm)
                   " — known: " (pr-str (vec (sort (keys token-endpoints))))))
          ctx)
      ;; The API's /oauth/token proxies client_credentials only, so a
      ;; user token comes from the realm itself. A public client may
      ;; run the password grant on its client id alone, and the token
      ;; it mints carries that client's azp — which is what
      ;; `authenticate` discriminates a user JWT on.
      (let [res (http/request
                 {:method :post
                  :url endpoint
                  :headers {"content-type" "application/x-www-form-urlencoded"}
                  :body (form-urlencode {:grant_type "password"
                                         :client_id client-id
                                         :username username
                                         :password password
                                         :scope (or scope "openid")})})
            body (http/res->edn res)
            token (:access_token body)]
        (when-not token
          (is false
              (str ":auth/mint-user-token failed the password grant at "
                   endpoint
                   " — status: " (:status res)
                   " body: " (pr-str body))))
        (cond-> ctx
                as
                (capture as token))))))

(defmethod dispatch :auth/sign-token
  [{:keys [captures signing-key token-endpoints] :as ctx}
   {:keys [realm claims kid as]}]
  (let [{:keys [realm claims kid]}
        (refs/resolve-all captures {:realm realm :claims claims :kid kid})
        ;; The issuer is the token endpoint without its OpenID suffix,
        ;; so the two can never name different realms.
        issuer (some-> (get token-endpoints realm)
                       (str/replace token-path ""))
        now-s (quot (utility/now) 1000)
        token (jwt/sign (merge {:iss issuer :iat now-s :exp (+ now-s 3600)}
                               claims)
                        (.getPrivate ^KeyPair signing-key)
                        {:alg :rs256
                         :header {:kid (or kid (str (utility/uuidv7)))
                                  :alg "RS256"
                                  :typ "JWT"}})]
    (cond-> ctx
            as
            (capture as token))))

(def ^:private admin-service-client
  "The operator service-account client both test realms seed. The
  Admin API reads the caller's realm roles rather than an API
  audience, so the grant asks for `realm-roles`."
  {:client-id "queenswood-admin"
   :client-secret "queenswood-admin-test-secret"
   :scope "realm-roles"})

(def ^:private rotated-key-component
  "A second active RSA provider, at a higher priority than the one
  the realm imported, so Keycloak signs subsequent tokens with it
  under a new `kid`. The imported provider stays active, so tokens
  minted before the addition still verify."
  {:name "rotated-rsa"
   :providerId "rsa-generated"
   :providerType "org.keycloak.keys.KeyProvider"
   :config
   {:priority ["200"] :active ["true"] :enabled ["true"] :algorithm ["RS256"]}})

(defn- admin-base-url
  "Keycloak's root, derived from the realm's token endpoint by
  removing the `/realms/<realm>` tail the issuer carries. Taken from
  the same configuration the verifier uses rather than a second one,
  so an admin call can never reach a different Keycloak from the one
  minting the tokens."
  [token-endpoints realm]
  (some-> (get token-endpoints realm)
          (str/replace (str "/realms/" (name realm) token-path) "")))

(defmethod dispatch :keycloak/add-signing-key
  [{:keys [token-endpoints] :as ctx} {:keys [realm as]}]
  (if-let [base (admin-base-url token-endpoints realm)]
    (let [token (-> (dispatch ctx
                              {:command :auth/mint-token
                               :for admin-service-client
                               :as ::admin-token})
                    (get-in [:captures ::admin-token]))
          res (http/request
               {:method :post
                :url (str base "/admin/realms/" (name realm) "/components")
                :headers {"content-type" "application/json"
                          "authorization" (str "Bearer " token)}
                :body (json/write-str rotated-key-component)})]
      (if (= 201 (:status res))
        (cond-> ctx
                as
                (capture as
                         (last (str/split (get-in res [:headers :location] "")
                                          #"/"))))
        (do (is false
                (str ":keycloak/add-signing-key was refused by " base
                     " — status: " (:status res)
                     " body: " (pr-str (http/res->edn res))))
            ctx)))
    (do (is false
            (str ":keycloak/add-signing-key knows no token endpoint for realm "
                 (pr-str realm)
                 " — known: " (pr-str (vec (sort (keys token-endpoints))))))
        ctx)))

(defmethod dispatch :webhook/open-receiver
  [{:keys [receiver run-id counter] :as ctx} {:keys [as]}]
  (capture ctx as {:address (str (:url receiver) "/r/" run-id "-" counter)}))

(defmethod dispatch :webhook/receiver-answers
  [{:keys [captures receiver] :as ctx} step]
  (let [{:keys [address status]} (refs/resolve-all captures step)
        path (.getPath (java.net.URI. ^String address))]
    (swap! (:answers receiver) assoc path status)
    ctx))

(defn- received-at
  [received address]
  (let [path (.getPath (java.net.URI. ^String address))]
    (filterv (fn [request] (= path (:path request))) @received)))

(defn- matching
  [requests matcher]
  (filterv (fn [request]
             (standalone/match? matcher
                                (json/read-str (:body request)
                                               :key-fn
                                               keyword)))
           requests))

(defn- signed?
  "Whether the request carries a signature over its id, timestamp and
  body under `secret`, as the Standard Webhooks specification has a
  tenant check it."
  [secret {:keys [headers body]}]
  (let [material (.decode (Base64/getUrlDecoder)
                          (str/replace-first secret #"^whsec_" ""))
        mac (doto (Mac/getInstance "HmacSHA256")
              (.init (SecretKeySpec. material "HmacSHA256")))
        content (str (get headers "webhook-id")
                     "."
                     (get headers "webhook-timestamp")
                     "."
                     body)
        expected (str "v1,"
                      (.encodeToString (Base64/getEncoder)
                                       (.doFinal mac
                                                 (.getBytes content
                                                            "UTF-8"))))]
    (some #{expected}
          (str/split (get headers "webhook-signature" "") #" "))))

(defn- delivery
  [{:keys [headers body]}]
  {:headers (update-keys headers keyword)
   :body (json/read-str body :key-fn keyword)})

(defmethod dispatch :webhook/await-delivery
  [{:keys [captures receiver await-timeout-ms] :as ctx} step]
  (let [{:keys [address where secret timeout-ms as] n :count}
        (refs/resolve-all captures step)
        n (or n 1)
        matcher (expand-matchers (or where {}))
        {:keys [done? value]}
        (await/until
         {:timeout-ms (or timeout-ms await-timeout-ms)}
         (fn [] (matching (received-at (:received receiver) address) matcher))
         (fn [requests] (<= n (count requests))))]
    (if done?
      (do (is (= n (count value))
              (str ":webhook/await-delivery expected " n
                   " delivery(s) to " address
                   ", received " (count value)
                   ": " (pr-str
                         (mapv
                          (fn [request]
                            (-> (json/read-str (:body request) :key-fn keyword)
                                (select-keys [:id :kind :resource-id])
                                (assoc :webhook-id
                                       (get-in request
                                               [:headers
                                                "webhook-id"]))))
                          value))))
          (when secret
            (doseq [request value]
              (is (signed? secret request)
                  (str "a delivery to " address
                       " does not verify under its endpoint's secret: "
                       (pr-str (:headers request))))))
          (cond-> ctx
                  as
                  (capture as (mapv delivery value))))
      (do (is false
              (str ":webhook/await-delivery timed out waiting for " n
                   " delivery(s) to " address
                   "; received " (count value)))
          ctx))))

(defmethod dispatch :assert/equals
  [{:keys [captures] :as ctx} step]
  (let [{:keys [actual expected]} (refs/resolve-all captures step)]
    (is (= expected actual)
        (str ":assert/equals\n  expected: " (pr-str expected)
             "\n  actual: " (pr-str actual))))
  ctx)
