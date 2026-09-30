(ns ^:eftest/synchronized
    com.repldriven.queenswood.test-api-scenarios.interface-test
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
    [com.repldriven.queenswood.clearbank-adapter.interface :as
     clearbank-adapter]
    [com.repldriven.queenswood.clearbank-simulator.interface :as
     clearbank-simulator]
    ;; The closed-control deftest sets up a state no route reaches. This
    ;; brick belongs to the development project alone, and the namespace
    ;; already loads api.api, which requires both of these.
    ;; enforce-idioms: brick-test-scope -- see above.
    [com.repldriven.queenswood.form3-adapter.interface :as form3-adapter]
    [com.repldriven.queenswood.form3-simulator.interface :as form3-simulator]
    [com.repldriven.queenswood.ledger-account.interface :as ledger-accounts]
    [com.repldriven.queenswood.modulr-adapter.interface :as modulr-adapter]
    [com.repldriven.queenswood.modulr-simulator.interface :as
     modulr-simulator]
    [com.repldriven.queenswood.onfido-adapter.interface :as onfido-adapter]
    [com.repldriven.queenswood.onfido-simulator.interface :as
     onfido-simulator]
    ;; enforce-idioms: brick-test-scope -- see the note above.
    [com.repldriven.queenswood.policy.interface :as policy]
    [com.repldriven.queenswood.uk-companies-house-simulator.interface :as
     ukch-simulator]
    [com.repldriven.queenswood.zyphe-adapter.interface :as zyphe-adapter]
    [com.repldriven.queenswood.zyphe-simulator.interface :as zyphe-simulator]

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
    [clojure.string :as str]
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
                clearbank-simulator/app)
      (assoc-in [:system/defs :clearbank-adapter-server :handler]
                clearbank-adapter/app)
      (assoc-in [:system/defs :form3-simulator-server :handler]
                form3-simulator/app)
      (assoc-in [:system/defs :form3-adapter-server :handler] form3-adapter/app)
      (assoc-in [:system/defs :modulr-simulator-server :handler]
                modulr-simulator/app)
      (assoc-in [:system/defs :modulr-adapter-server :handler]
                modulr-adapter/app)
      (assoc-in [:system/defs :onfido-simulator-server :handler]
                onfido-simulator/app)
      (assoc-in [:system/defs :onfido-adapter-server :handler]
                onfido-adapter/app)
      (assoc-in [:system/defs :zyphe-simulator-server :handler]
                zyphe-simulator/app)
      (assoc-in [:system/defs :zyphe-adapter-server :handler]
                zyphe-adapter/app)
      (assoc-in [:system/defs :uk-companies-house-simulator-server :handler]
                ukch-simulator/app)))

(defn- scenario-files
  "Walk `dir`, `test-api-scenarios/scenarios/` unless named, recursively,
  returning `{:file File :relative \"<sub>/<name>.edn\"}` entries sorted
  by relative path so domain-grouped runs stay deterministic."
  ([] (scenario-files "test-api-scenarios/scenarios"))
  ([dir]
   (let [root (io/file (.getFile (io/resource dir)))
         prefix-len (inc (count (.getPath root)))]
     (->> (file-seq root)
          (filter (fn [f]
                    (and (.isFile ^java.io.File f)
                         (.endsWith (.getName f) ".edn"))))
          (map (fn [f]
                 {:file f
                  :relative (subs (.getPath f) prefix-len)}))
          (sort-by :relative)))))

(defn- span->map
  "One finished span as data: enough to rebuild the tree and time
  each node, with its attributes as strings."
  [^SpanData s]
  (let [ctx (.getSpanContext s)
        parent (.getParentSpanContext s)]
    {:trace-id (.getTraceId ctx)
     :span-id (.getSpanId ctx)
     :parent-id (when (.isValid parent) (.getSpanId parent))
     :name (.getName s)
     :kind (str (.getKind s))
     :start-ns (.getStartEpochNanos s)
     :end-ns (.getEndEpochNanos s)
     :attributes (into {}
                       (map (fn [[^AttributeKey k v]] [(.getKey k) (str v)]))
                       (.asMap (.getAttributes s)))}))

(defn- dump-spans!
  "Write every finished span as one JSON line to `path`, when the rig
  names one — its `span-dump` component reads `QW_SPAN_DUMP`. The
  run's timing evidence, for reading where a request spends its time."
  [path spans]
  (when path
    (with-open [w (io/writer path)]
      (doseq [s spans]
        (.write w ^String (json/write-str (span->map s)))
        (.write w "\n")))
    (log/info "api scenario spans written" {:path path :count (count spans)})))

(defn- fdb-config
  "The booted system's own FDB handles, as the `txn-or-config` map a
  brick interface takes. Lets a test reach a transition no route
  exposes against the same records the API is serving."
  [sys]
  {:record-db (system/instance sys [:fdb :record-db])
   :record-store (system/instance sys [:fdb :store])})

(defn- post-json
  "POST `body` as JSON to `path` on the booted API, bearing `token`,
  `idempotency-key` and, when given, the `Bank-Id` header naming
  `bank-id`, and return `{:status :body}`."
  [base-url token idempotency-key bank-id path body]
  (let [res (http/request {:method :post
                           :url (str base-url path)
                           :headers (cond-> {"content-type" "application/json"
                                             "authorization" (str "Bearer "
                                                                  token)
                                             "idempotency-key" idempotency-key}
                                            bank-id
                                            (assoc "bank-id" bank-id))
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
                            "ik-closed-control-bank-001" nil
                            "/v1/banks" {:name "Closed Control Bank"
                                         :status "test"
                                         :tier "micro"
                                         :currencies ["GBP"]})
         bank-id (get-in created [:body :bank-id])]
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
                 ;; The money lands on the house account, an own-funds
                 ;; product, so its credit leg fans out to the 3100 control
                 ;; just closed. The customer leg is never recorded without
                 ;; its mirror, so the posting fails outright rather than
                 ;; landing single-sided.
                 _ (let [refused (post-json base-url
                                            admin-token
                                            "ik-closed-control-inbound-001"
                                            bank-id
                                            "/v1/simulate/inbound-transfer"
                                            {:amount 250000 :currency "GBP"})]
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

(defn- skipped-tags
  "The tags naming what a payment provider cannot run, read off its
  declaration: telling of an inbound once settled, admitting one before
  it settles, and screening an outbound itself."
  [{:keys [inbound screening]}]
  (cond-> #{}
          (not= "notified" inbound)
          (conj :inbound-notified)

          (not= "admitted" inbound)
          (conj :inbound-admitted)

          (not= "provider" screening)
          (conj :screened)))

(defn- idv-skipped-tags
  "The tags naming what an IDV provider cannot run, read off its
  declaration: refusing a session opened without the email it needs."
  [{:keys [needs]}]
  (cond-> #{}
          (not (some #{"email"} needs))
          (conj :needs-email)))

(def ^:private unbuilt
  "The tags naming what a provider's adapter does not carry yet, by
  provider, as distinct from what its declaration rules out: ClearBank's
  adapter and simulator return no outbound payment."
  {"clearbank" #{:outbound-returned}})

(defn- idv-runs
  "The party scenarios on each IDV provider offered but the default,
  with the default payment provider's simulator."
  [sys payment-run]
  (let [{:keys [default providers]} (system/instance sys
                                                     [:idv-provider
                                                      :providers])
        party-files (filter (fn [{:keys [relative]}]
                              (re-find #"^parties/" relative))
                            (scenario-files))]
    (for [provider (sort (map (comp name :provider) (vals providers)))
          :when (not= (name default) provider)]
      (assoc payment-run
             :provider provider
             :files party-files
             :skips (into (:skips payment-run)
                          (idv-skipped-tags (system/instance
                                             sys
                                             [:idv-provider
                                              (keyword provider)])))
             :providers {:idv provider}
             :key-suffix provider))))

(defn- payment-runs
  "Every scenario on the default payment provider, then the payment and
  payee-check scenarios on each other provider offered, each run with
  its provider's simulator and the tags it skips."
  [sys]
  (let [{:keys [default providers]} (system/instance sys
                                                     [:payment-provider
                                                      :providers])
        run (fn [provider files]
              {:provider (name provider)
               :files files
               :skips (into (skipped-tags (system/instance
                                           sys
                                           [:payment-provider
                                            (keyword provider)]))
                            (get unbuilt (name provider)))
               :payment-simulator-url (system/instance
                                       sys
                                       [(keyword (str (name provider)
                                                      "-simulator-server"))
                                        :http-url])})
        payment-files (filter (fn [{:keys [relative]}]
                                (re-find #"^(payments|payee-checks)/"
                                         relative))
                              (scenario-files))]
    (cons (run default (scenario-files))
          (for [provider (sort (map (comp name :provider) (vals providers)))
                :when (not= (name default) provider)]
            (assoc (run provider payment-files)
                   :providers {:payment provider}
                   :key-suffix provider)))))

(defn- runs
  "The payment runs, then the IDV runs, which take the default payment
  provider's."
  [sys]
  (let [default-idv (:default (system/instance sys [:idv-provider :providers]))
        idv-skips (idv-skipped-tags (system/instance sys
                                                     [:idv-provider
                                                      (keyword default-idv)]))
        payment (payment-runs sys)]
    (concat (map (fn [run] (update run :skips into idv-skips)) payment)
            (idv-runs sys (first payment)))))

(deftest api-scenarios-test
  ;; One test system serves every scenario, on every payment provider.
  ;; Per-scenario isolation comes from a fresh runner context (own
  ;; captures map), so scenarios cannot read each other's state.
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
           key-pair (signing-key)
           mail-url (system/instance sys [:smtp :container-api-url])
           zyphe-simulator-url (system/instance sys
                                                [:zyphe-simulator-server
                                                 :http-url])]
       ;; The seam remembers which ids it has already lost, and it
       ;; outlives the system this boot tears down. Clearing it here
       ;; keeps a second run in the same JVM — a REPL re-run — losing
       ;; the replies the lost-reply scenarios need to go missing.
       (fault/reset-lost!)
       (doseq [{:keys [provider files skips payment-simulator-url providers
                       key-suffix]}
               (runs sys)
               {:keys [relative]} files]
         (let [resource-path (str "test-api-scenarios/scenarios/" relative)]
           (testing (str provider " " relative)
             (nom-test> [loaded (SUT/from-resource resource-path)
                         _ (when-not (some skips (:tags loaded))
                             (log/info "api scenario running"
                                       {:provider provider
                                        :file relative
                                        :name (:name loaded)
                                        :steps (count (SUT/steps loaded))})
                             (SUT/run-scenario
                              (SUT/fresh-context
                               {:base-url base-url
                                :admin-token admin-token
                                :token-endpoints endpoints
                                :signing-key key-pair
                                :mail-url mail-url
                                :payment-simulator-url payment-simulator-url
                                :zyphe-simulator-url zyphe-simulator-url
                                :providers providers
                                :key-suffix key-suffix
                                :run-id (str (util/uuidv7))})
                              resource-path))]))))
       (testing "the run is traced end to end"
         (let [spans (test-telemetry/finished-spans
                      (system/instance sys [:telemetry :otel-sdk]))
               names (frequencies (map #(.getName ^SpanData %) spans))
               method (fn [^SpanData s] (first (str/split (.getName s) #" " 2)))
               methods (frequencies (map method spans))]
           (dump-spans! (system/instance sys [:test-api-scenarios :span-dump])
                        spans)
           ;; Every scenario drives at least one request, so this floor
           ;; holds however many scenarios there are.
           (is (>= (count spans) (count files)))
           ;; Server spans: the API is instrumented on the request path,
           ;; each named for the route it matched.
           (is (pos? (get methods "GET" 0)))
           (is (pos? (get methods "POST" 0)))
           (is (some #(re-matches #"GET /v1/.*\{.+\}.*" %) (keys names))
               "a server span is named for a route template, not a path")
           (is (pos? (get names "process-command" 0)))
           ;; The trace carries from the HTTP thread across the bus into
           ;; the processor, which is the point of propagating
           ;; traceparent and the thing nothing else here would notice
           ;; breaking.
           ;;
           ;; Onward too: a command sent in reaction to an event, such as
           ;; `open-payment-account` from `cash-account-status-changed`,
           ;; carries the event's trace to the adapter, and the relay
           ;; resumes it from the intent when it calls the provider.
           (let [trace-id (fn [^SpanData s] (.getTraceId (.getSpanContext s)))
                 server? #(#{"GET" "POST" "PUT" "DELETE"} (method %))
                 traces (set (map trace-id (filter server? spans)))
                 named (fn [n] (filter #(= n (.getName ^SpanData %)) spans))
                 joined (fn [n]
                          (count (filter #(contains? traces (trace-id %))
                                         (named n))))]
             (is (pos? (joined "process-command")))
             (is (some #(and (= "open-payment-account"
                                (.get (.getAttributes ^SpanData %)
                                      (AttributeKey/stringKey "command")))
                             (contains? traces (trace-id %)))
                       (named "process-command"))
                 "a provider command joins the request that caused it")
             (is (some #(and (str/ends-with? (.getName ^SpanData %) "-outbound")
                             (contains? traces (trace-id %)))
                       spans)
                 "a relay's call to its provider joins the request's trace")
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
             ;;
             ;; An account's opening, closing and rotation complete when
             ;; the payment provider reports back, in a write made while
             ;; handling that report. The report is a webhook the provider
             ;; sends in its own time, carrying no trace of ours, so the
             ;; write it leads to starts a trace rather than joining a
             ;; request's. Only a write made handling a command is caused
             ;; by a request, so the property holds over events whose
             ;; writer's nearest handling span is a `process-command`.
             (let [event-attr (fn [^SpanData s]
                                (.get (.getAttributes s)
                                      (AttributeKey/stringKey "event")))
                   carried? (fn [^SpanData s]
                              (.isValid (.getParentSpanContext s)))
                   by-id (into {}
                               (map (fn [^SpanData s] [(.getSpanId
                                                        (.getSpanContext s))
                                                       s]))
                               spans)
                   commanded? (fn [^SpanData s]
                                (loop [id (.getSpanId (.getParentSpanContext
                                                       s))]
                                  (let [^SpanData ancestor (by-id id)
                                        n (some-> ancestor
                                                  .getName)]
                                    (cond
                                     (nil? ancestor)
                                     false

                                     (= "process-command" n)
                                     true

                                     (= "process-event" n)
                                     false

                                     :else
                                     (recur (.getSpanId
                                             (.getParentSpanContext
                                              ancestor)))))))
                   of-event (fn [n]
                              (filter #(and (= n (event-attr %))
                                            (carried? %)
                                            (commanded? %))
                                      (named "process-event")))
                   account-events (of-event "cash-account-status-changed")]
               (is (pos? (count account-events)))
               (is (= (count account-events)
                      (count (filter #(contains? traces (trace-id %))
                                     account-events))))))))))))
