(ns com.repldriven.queenswood.test-api-scenarios.verbs
  (:require
    [com.repldriven.queenswood.test-api-scenarios.refs :as refs]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as utility]

    [buddy.sign.jwt :as jwt]
    [matcher-combinators.matchers :as m]
    [matcher-combinators.standalone :as standalone]

    [clojure.string :as str]
    [clojure.test :refer [is]]
    [clojure.walk :as walk])
  (:import
    (java.security KeyPair)))

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

(defn- build-request
  [{:keys [base-url] :as ctx}
   {:keys [method url path path-params query-params body form auth headers]}]
  (let [token (resolve-auth ctx auth)
        base-headers (cond-> {}
                             body
                             (assoc "Content-Type" "application/json")
                             form
                             (assoc "Content-Type"
                                    "application/x-www-form-urlencoded")
                             token
                             (assoc "Authorization" (str "Bearer " token)))]
    (cond-> {:method method
             :url (resolve-url base-url url path path-params)
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

(defmulti dispatch
  "Scenario step dispatch. `:api/*` methods drive the bank API over
  HTTP; `:assert/*` methods check the previous response."
  (fn [_ctx command] (:command command)))

(defmethod dispatch :api/request
  [{:keys [captures] :as ctx} {:keys [request as] :as step}]
  (let [resolved (refs/resolve-all captures request)
        res (http/request (build-request ctx resolved))
        body (http/res->edn res)
        response {:status (:status res) :body body :headers (:headers res)}
        ctx' (cond-> (assoc ctx :last-response response)
                     as
                     (assoc-in [:captures as] body))]
    (if-let [expect (:assert step)]
      (dispatch ctx' {:command :assert/response :assert expect})
      ctx')))

(defmethod dispatch :wait
  [ctx {:keys [duration-ms]}]
  (Thread/sleep ^long duration-ms)
  ctx)

(def ^:private default-poll-timeout-ms 10000)
(def ^:private default-poll-interval-ms 50)

(defmethod dispatch :api/poll
  [{:keys [captures] :as ctx} {:keys [request until timeout-ms interval-ms as]}]
  (let [resolved-request (refs/resolve-all captures request)
        until-matcher (expand-matchers (refs/resolve-all captures until))
        timeout (or timeout-ms default-poll-timeout-ms)
        interval (or interval-ms default-poll-interval-ms)
        deadline (+ (utility/now) timeout)]
    (loop [last-response nil]
      (let [res (http/request (build-request ctx resolved-request))
            body (http/res->edn res)
            response {:status (:status res) :body body :headers (:headers res)}]
        (cond
         (standalone/match? until-matcher response)
         (cond-> (assoc ctx :last-response response)
                 as
                 (assoc-in [:captures as] body))

         (>= (utility/now) deadline)
         (do (is false
                 (str "poll timed out after "
                      timeout
                      "ms waiting for response"
                      " to match\n  expected: " (pr-str until)
                      "\n  last actual: " (pr-str (or last-response response))))
             ctx)

         :else
         (do (Thread/sleep ^long interval)
             (recur response)))))))

(defmethod dispatch :assert/status
  [{:keys [last-response] :as ctx} {[expected] :args}]
  (is (= expected (:status last-response))
      (str "expected status " expected
           " got " (:status last-response)
           "; body: " (pr-str (:body last-response))))
  ctx)

(defmethod dispatch :assert/response
  [{:keys [captures last-response] :as ctx} {expectation :assert}]
  (let [{:keys [status body problem]} (refs/resolve-all captures expectation)
        actual-body (:body last-response)]
    (when status
      (is (= status (:status last-response))
          (str "expected status " status
               " got " (:status last-response)
               "; body: " (pr-str actual-body))))
    (when body (assert-match body actual-body "body"))
    (when problem
      (assert-match (merge {:type [:m/any] :title [:m/any]} problem)
                    actual-body
                    "problem-details")))
  ctx)

(defmethod dispatch :auth/mint-token
  [{:keys [base-url captures] :as ctx} {:keys [for as]}]
  (let [{:keys [client-id client-secret status scope]}
        (refs/resolve-all captures for)
        scope-value
        (or scope
            (if (= :live status) "queenswood-api-live" "queenswood-api-test"))
        res (http/request {:method :post
                           :url (str base-url "/oauth/token")
                           :headers {"content-type"
                                     "application/x-www-form-urlencoded"}
                           :body (str "grant_type=client_credentials"
                                      "&client_id=" client-id
                                      "&client_secret=" client-secret
                                      "&scope=" scope-value)})
        body (http/res->edn res)
        token (:access_token body)]
    (when-not token
      (is false
          (str ":auth/mint-token failed to exchange credentials at /oauth/token"
               " — status: " (:status res)
               " body: " (pr-str body))))
    (cond-> ctx
            as
            (assoc-in [:captures as] token))))

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
                (assoc-in [:captures as] token))))))

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
            (assoc-in [:captures as] token))))
