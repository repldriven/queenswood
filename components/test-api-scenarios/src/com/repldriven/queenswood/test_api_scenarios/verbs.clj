(ns com.repldriven.queenswood.test-api-scenarios.verbs
  (:require
    [com.repldriven.queenswood.test-api-scenarios.refs :as refs]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as utility]

    [matcher-combinators.matchers :as m]
    [matcher-combinators.standalone :as standalone]

    [clojure.string :as str]
    [clojure.test :refer [is]]
    [clojure.walk :as walk]))

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

(defmulti dispatch
  "Scenario step dispatch. `:api/*` methods drive the bank API over
  HTTP; `:assert/*` methods check the previous response.

  `:api/race` sends one request `:count` times at once and asserts the
  idempotency invariant over the answers rather than their timing: see
  its own method."
  (fn [_ctx command] (:command command)))

(defmethod dispatch :api/request
  [{:keys [captures] :as ctx} {:keys [request as] :as step}]
  (let [resolved (refs/resolve-all captures request)
        response (send-once ctx resolved)
        ctx' (cond-> (assoc ctx :last-response response)
                     as
                     (assoc-in [:captures as] (:body response)))]
    (if-let [expect (:assert step)]
      (dispatch ctx' {:command :assert/response :assert expect})
      ctx')))

(defmethod dispatch :api/race
  [{:keys [captures] :as ctx} {:keys [request as] n :count :as step}]
  (let [{:keys [status fresh]} (refs/resolve-all captures (:assert step))
        resolved (refs/resolve-all captures request)
        responses (->> (repeatedly n #(future (send-once ctx resolved)))
                       (into [])
                       (mapv deref))
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
            (assoc-in [:captures as] (into (vec fresh-responses) others)))))

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
