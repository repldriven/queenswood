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

    [com.repldriven.queenswood.test-api-scenarios.interface :as SUT]

    [com.repldriven.queenswood.api.api :as api]
    [com.repldriven.queenswood.clearbank-adapter.interface :as cb-adapter]
    [com.repldriven.queenswood.clearbank-simulator.interface :as cb-simulator]
    [com.repldriven.queenswood.onfido-adapter.interface :as onfido-adapter]
    [com.repldriven.queenswood.onfido-simulator.interface :as onfido-simulator]
    [com.repldriven.queenswood.uk-companies-house-simulator.interface :as
     ukch-simulator]

    [com.repldriven.mono.http-client.interface :as http]
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
    (io.opentelemetry.sdk.trace.data SpanData)))

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

(defn- patch-handlers
  [defs]
  (-> defs
      (assoc-in [:system/defs :server :handler] api/app)
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
           admin-token (mint-admin-token base-url)]
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
             ;; Scoped to one event name rather than every
             ;; `process-event` span. Rigs share a Kafka
             ;; testcontainer — same broker, same topics, same group
             ;; ids — so this exporter also collects events another
             ;; rig published. Those carry no traceparent and can
             ;; never join, which swamps a ratio taken over the whole
             ;; set once a second rig publishes the same event.
             ;;
             ;; cash-account-status-changed is this rig's alone (no
             ;; other rig wires cash-accounts) and every one is caused
             ;; by an API request, so the property is exact: all of
             ;; them join, not most.
             (let [event-attr (fn [^SpanData s]
                                (.get (.getAttributes s)
                                      (AttributeKey/stringKey "event")))
                   of-event (fn [n]
                              (filter #(= n (event-attr %))
                                      (named "process-event")))
                   account-events (of-event "cash-account-status-changed")]
               (is (pos? (count account-events)))
               (is (= (count account-events)
                      (count (filter #(contains? traces (trace-id %))
                                     account-events))))))))))))
