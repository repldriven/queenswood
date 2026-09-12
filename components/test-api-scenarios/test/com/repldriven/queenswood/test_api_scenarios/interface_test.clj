(ns com.repldriven.queenswood.test-api-scenarios.interface-test
  "Single-boot runner for EDN-defined API scenarios.

  Boots one bank-api system, then iterates every `.edn` file under
  `bank-test-api-scenarios/scenarios/` on the classpath. Each
  scenario gets its own runner context (fresh captures map) so
  scenarios cannot leak state into one another; the booted system
  is shared to amortise startup cost."
  (:require
    [com.repldriven.queenswood.test-api-scenarios.system]

    [com.repldriven.queenswood.test-api-scenarios.fault :as fault]
    [com.repldriven.queenswood.test-api-scenarios.interface :as SUT]

    [com.repldriven.queenswood.api.api :as api]
    [com.repldriven.queenswood.clearbank-adapter.interface :as cb-adapter]
    [com.repldriven.queenswood.clearbank-simulator.interface :as cb-simulator]
    ;; The closed-control deftest sets up a state no route reaches. This
    ;; brick belongs to the development project alone, and the namespace
    ;; already loads api.api, which requires both of these.
    ;; enforce-idioms: brick-test-scope -- see above.
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.onfido-adapter.interface :as onfido-adapter]
    [com.repldriven.queenswood.onfido-simulator.interface :as onfido-simulator]
    ;; enforce-idioms: brick-test-scope -- see the note above.
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.uk-companies-house-simulator.interface :as
     ukch-simulator]

    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-telemetry.interface :as test-telemetry]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]
    [com.repldriven.mono.utility.interface :as util]

    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]])
  (:import
    (io.opentelemetry.api.common AttributeKey)
    (io.opentelemetry.sdk.trace.data SpanData)
    (java.security KeyPairGenerator)))

(defn- mint-admin-token
  "Exchange the seeded queenswood-admin client_credentials for an
  admin-roled service JWT against the queenswood realm. The
  service-account-queenswood-admin user has the `admin` realm role
  assigned in the test realm JSON, and the queenswood-admin client
  carries a realm-roles protocol mapper, so the minted token's
  `realm_access.roles` includes `admin` — bank-api's service-auth
  picks that up and grants the `:admin` role (admins carry no
  implicit `:bank-id`), giving the same principal shape as the
  legacy env-var admin bearer."
  [base-url]
  (let [res (http/request
             {:method :post
              :url (str base-url "/oauth/token")
              :headers {"content-type" "application/x-www-form-urlencoded"}
              :body (str "grant_type=client_credentials"
                         "&client_id=queenswood-admin"
                         "&client_secret=queenswood-admin-test-secret"
                         "&scope=queenswood-api-test+realm-roles")})
        body (http/res->edn res)]
    (or (:access_token body)
        ;; nosemgrep: no-raw-throw
        (throw (ex-info "Failed to mint scenario admin token"
                        {:status (:status res) :body body})))))

(defn- token-endpoints
  "Realm keyword → that realm's OpenID token endpoint, read off the
  booted providers so the URL and the issuer the API verifies against
  can never drift apart."
  [sys]
  (into {}
        (map (fn [[realm path]]
               [realm
                (str (identity-provider/get-issuer (system/instance sys path))
                     "/protocol/openid-connect/token")]))
        {:queenswood [:keycloak :identity-provider]
         :queenswood-ops [:keycloak :identity-provider-ops]}))

(defn- signing-key
  "One RSA keypair for the whole run. `fresh-context` is called per
  scenario file, so generating it there would be one key generation
  per scenario for a key only the token-forging verb uses."
  []
  (let [generator (KeyPairGenerator/getInstance "RSA")]
    (.initialize generator 2048)
    (.generateKeyPair generator)))

(defn- app-with-fault
  "The bank API, with the lost-reply seam spliced into the interceptor
  list the server hands its routes. Test-only, and inert until a
  scenario sends an `ik-lost-reply-` key."
  [ctx]
  (api/app (update ctx
                   :interceptors
                   (fn [interceptors]
                     (vec (concat interceptors [fault/lose-reply]))))))

(defn- patch-handlers
  [defs]
  (-> defs
      (assoc-in [:system/defs :server :handler] app-with-fault)
      (assoc-in [:system/defs :clearbank-simulator-server :handler]
                cb-simulator/app)
      (assoc-in [:system/defs :clearbank-adapter-server :handler]
                cb-adapter/app)
      (assoc-in [:system/defs :onfido-simulator-server :handler]
                onfido-simulator/app)
      (assoc-in [:system/defs :onfido-adapter-server :handler]
                onfido-adapter/app)
      (assoc-in [:system/defs :uk-companies-house-simulator-server :handler]
                ukch-simulator/app)))

(defn- scenario-files
  "Walk `bank-test-api-scenarios/scenarios/` recursively, returning
  `{:file File :relative \"<sub>/<name>.edn\"}` entries sorted by
  relative path so domain-grouped runs stay deterministic."
  []
  (let [root (io/file (.getFile (io/resource
                                 "test-api-scenarios/scenarios")))
        prefix-len (inc (count (.getPath root)))]
    (->> (file-seq root)
         (filter (fn [f]
                   (and (.isFile ^java.io.File f)
                        (.endsWith (.getName f) ".edn"))))
         (map (fn [f]
                {:file f
                 :relative (subs (.getPath f) prefix-len)}))
         (sort-by :relative))))

(defn- fdb-config
  "The booted system's own FDB handles, as the `txn-or-config` map a
  brick interface takes. Lets a test reach a transition no route
  exposes against the same records the API is serving."
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(defn- post-json
  "POST `body` as JSON to `path` on the booted API, bearing `token`
  and `idempotency-key`, and return `{:status :body}`."
  [base-url token idempotency-key path body]
  (let [res (http/request {:method :post
                           :url (str base-url path)
                           :headers {"content-type" "application/json"
                                     "authorization" (str "Bearer " token)
                                     "idempotency-key" idempotency-key}
                           :body (json/write-str body)})]
    {:status (:status res) :body (http/res->edn res)}))

(deftest closed-control-refuses-a-posting-test
  ;; The 409 half of the closed-control rule. No route or command
  ;; closes a ledger account, so an EDN scenario cannot set the state
  ;; up: the close runs through the brick's interface against the
  ;; booted system's own FDB config, and the posting that meets it
  ;; goes over HTTP. Closing is gated on the `:ledger-account` close
  ;; capability, which the micro tier denies and the platform tier
  ;; grants, so the platform policies are passed explicitly the way
  ;; bank bootstrap passes them to `new-account`.
  (with-test-system
   [sys
    ["classpath:test-api-scenarios/application-test.yml"
     patch-handlers]]
   (let [jetty (system/instance sys [:server :jetty-adapter])
         base-url (server/http-local-url jetty)
         admin-token (mint-admin-token base-url)
         config (fdb-config sys)
         created (post-json base-url
                            admin-token
                            "ik-closed-control-bank-001"
                            "/v1/banks"
                            {:name "Closed Control Bank"
                             :status "live"
                             :tier "micro"
                             :currencies ["GBP"]})
         bank-id (get-in created [:body :bank-id])
         house-account-id (get-in created [:body :accounts 0 :account-id])]
     (is (= 201 (:status created)) (pr-str (:body created)))
     (nom-test> [policies (policy/get-effective-policies config {})
                 own-funds (ledger-accounts/find-by-code
                            config
                            bank-id
                            :gl-account-code-own-funds
                            "GBP")
                 closed (ledger-accounts/close-account config
                                                       bank-id
                                                       (:ledger-account-id
                                                        own-funds)
                                                       {:policies policies})
                 _ (is (= :ledger-account-status-closed (:status closed)))
                 ;; The house account is an own-funds product, so its
                 ;; credit leg fans out to the 3100 control just closed.
                 ;; The customer leg is never recorded without its mirror,
                 ;; so the posting fails outright rather than landing
                 ;; single-sided.
                 _ (let [refused (post-json base-url
                                            admin-token
                                            "ik-closed-control-inbound-001"
                                            (str "/v1/simulate/banks/"
                                                 bank-id
                                                 "/inbound-transfer")
                                            {:account-id house-account-id
                                             :amount 250000
                                             :currency "GBP"})]
                     (is (= 409 (:status refused)) (pr-str (:body refused)))
                     (is (= ":ledger-account/closed"
                            (get-in refused [:body :type]))))]))))

(deftest idempotency-keys-are-unique-across-files-test
  ;; Bank creation sits in the `:given` of almost every scenario file
  ;; and the admin principal is shared by the whole boot, so a key
  ;; literal that appears in two files replays the other file's bank
  ;; rather than creating one.
  (let [owners (reduce (fn [m {:keys [file relative]}]
                         (reduce
                          (fn [m [_ k]]
                            (update m k (fnil conj (sorted-set)) relative))
                          m
                          (re-seq #"\"(ik-[^\"]+)\"" (slurp file))))
                       {}
                       (scenario-files))
        reused (into (sorted-map)
                     (filter (fn [[_ files]] (< 1 (count files))) owners))]
    (is (= {} reused)
        "an Idempotency-Key literal shared by two scenario files replays")))

(deftest api-scenarios-test
  ;; One test system serves every scenario. Per-scenario isolation
  ;; comes from a fresh runner context (own captures map), so
  ;; scenarios cannot read each other's state.
  (let [files (scenario-files)]
    (is (seq files) "expected scenarios on the classpath")
    (log/info "api scenarios starting" {:count (count files)})
    (with-test-system
     [sys
      ["classpath:test-api-scenarios/application-test.yml"
       patch-handlers]]
     (let [jetty (system/instance sys [:server :jetty-adapter])
           base-url (server/http-local-url jetty)
           admin-token (mint-admin-token base-url)
           endpoints (token-endpoints sys)
           key-pair (signing-key)]
       ;; The seam remembers which ids it has already lost, and it
       ;; outlives the system this boot tears down. Clearing it here
       ;; keeps a second run in the same JVM — a REPL re-run — losing
       ;; the replies the lost-reply scenarios need to go missing.
       (fault/reset-lost!)
       (doseq [{:keys [relative]} files]
         (let [resource-path (str "test-api-scenarios/scenarios/" relative)]
           (testing relative
             (nom-test> [loaded (SUT/from-resource resource-path)
                         _ (log/info "api scenario running"
                                     {:file relative
                                      :name (:name loaded)
                                      :steps (count (SUT/steps loaded))})
                         _ (SUT/run-scenario (SUT/fresh-context
                                              {:base-url base-url
                                               :admin-token admin-token
                                               :token-endpoints endpoints
                                               :signing-key key-pair
                                               :run-id (str (util/uuidv7))})
                                             resource-path)
                         _ (log/info "api scenario complete" {:file relative})]))))
       (testing "the run is traced end to end"
         (let [spans (test-telemetry/finished-spans
                      (system/instance sys [:telemetry :otel-sdk]))
               names (frequencies (map #(.getName ^SpanData %) spans))]
           ;; Every scenario drives at least one request, so this floor
           ;; holds however many scenarios there are.
           (is (>= (count spans) (count files)))
           ;; Server spans: the API is instrumented on the request path.
           (is (pos? (get names "GET" 0)))
           (is (pos? (get names "POST" 0)))
           (is (pos? (get names "process-command" 0)))
           ;; The trace carries from the HTTP thread across the bus into
           ;; the processor, which is the point of propagating
           ;; traceparent and the thing nothing else here would notice
           ;; breaking.
           ;;
           ;; Some, not all: a command a watcher dispatches in reaction
           ;; to a changelog entry (`submit-idv-check`, `submit-payment`)
           ;; opens its own trace, because the record change is what
           ;; caused it rather than any request thread (ADR-0008). Those
           ;; carry a `causation_id` naming the entity; the ones the API
           ;; dispatches carry `correlation_id` equal to their own id.
           (let [trace-id (fn [^SpanData s] (.getTraceId (.getSpanContext s)))
                 server? #(#{"GET" "POST" "PUT" "DELETE"}
                            (.getName ^SpanData %))
                 traces (set (map trace-id (filter server? spans)))
                 named (fn [n] (filter #(= n (.getName ^SpanData %)) spans))
                 joined (fn [n]
                          (count (filter #(contains? traces (trace-id %))
                                         (named n))))]
             (is (pos? (joined "process-command")))
             ;; Events too, since the outbox and changelog carry the
             ;; writer's traceparent.
             (is (pos? (joined "process-event")))
             ;; Scoped to one event name, and within it to the spans
             ;; that arrived under a traceparent. Rigs share one Kafka
             ;; testcontainer, and while this rig is up its test SDK is
             ;; the JVM's default tracer, so this exporter also collects
             ;; spans another rig opened: `webhook`'s tests publish
             ;; cash-account-status-changed on a local bus from an
             ;; envelope they build by hand. Those carry no traceparent,
             ;; so the span is a root whose trace holds no server span
             ;; and can never join. Every one of this rig's is caused by
             ;; an API request and carries the writer's, so the property
             ;; is exact over the carried ones: all of them join, not
             ;; most. The `pos?` floor still catches total loss; the
             ;; partial case, one event that lost its traceparent on the
             ;; way, is what the filter gives up.
             (let [event-attr (fn [^SpanData s]
                                (.get (.getAttributes s)
                                      (AttributeKey/stringKey "event")))
                   carried? (fn [^SpanData s]
                              (.isValid (.getParentSpanContext s)))
                   of-event (fn [n]
                              (filter #(and (= n (event-attr %)) (carried? %))
                                      (named "process-event")))
                   account-events (of-event "cash-account-status-changed")]
               (is (pos? (count account-events)))
               (is (= (count account-events)
                      (count (filter #(contains? traces (trace-id %))
                                     account-events))))))))))))
