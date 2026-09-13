(ns com.repldriven.queenswood.api.openapi-test
  "ADR-0014 makes full OpenAPI 3.x compliance the contract, and until
  this namespace nothing checked it: the pages workflow exports the
  document and validates nothing.

  Three things hold here. The document builds the way `export-spec`
  builds it, without booting a system. Every `$ref` in it resolves
  against the document itself. And it validates against the published
  OpenAPI 3.1 schema, read from `test-resources/openapi/` with the
  validator's remote fetching off, so the check needs no network.

  The document does not pass that validation today. What it already
  fails is recorded in `standing-gaps`, class by class, so a new
  departure fails this test while the recorded ones are closed by the
  work that owns the routes.

  Two things the validation cannot see hold as well: every `{var}` in a
  path template has an `in: path` parameter, and every parameter an
  operation listed at `4b5cc4a5`, recorded in
  `test-resources/openapi/parameters-4b5cc4a5.edn`, it still lists."
  (:require
    [com.repldriven.queenswood.api.api :as api]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.test-system.interface :refer [nom-test>]]

    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]])
  (:import
    (com.networknt.schema InputFormat SchemaLocation SchemaRegistry)
    (com.networknt.schema.resource SchemaIdResolvers$Builder
                                   SchemaLoader$Builder)
    (java.util.function Consumer)))

(def ^:private exported
  "The document as `export-spec` produces it: `api/app` with no
  interceptors and a GET of `/openapi.json`. A delay, so the tests
  below build it once between them."
  (delay (let [handler (api/app {:interceptors []})
               {:keys [status body]} (handler {:request-method :get
                                               :uri "/openapi.json"})]
           {:status status :body (slurp body)})))

;; --- every $ref resolves -------------------------------------------

(defn- refs
  [node]
  (cond
   (map? node)
   (into (if (string? (get node "$ref")) [(get node "$ref")] [])
         (mapcat refs (vals node)))

   (sequential? node)
   (into [] (mapcat refs node))

   :else
   []))

(defn- unescape
  ;; `~1` before `~0`, or `~01` unescapes to `/` rather than to `~1`.
  [segment]
  (-> segment
      (str/replace "~1" "/")
      (str/replace "~0" "~")))

(defn- resolves?
  [document ref]
  (and (str/starts-with? ref "#/")
       (not= ::missing
             (reduce (fn [node segment]
                       (cond
                        (map? node)
                        (get node segment ::missing)

                        (sequential? node)
                        (let [index (parse-long segment)]
                          (if (and index (< -1 index (count node)))
                            (nth node index)
                            (reduced ::missing)))

                        :else
                        (reduced ::missing)))
                     document
                     (map unescape (str/split (subs ref 2) #"/"))))))

(defn- unresolved
  "The `$ref`s naming nothing in `document`. A reference that is not a
  local pointer counts as unresolved: this document is served with no
  companion, so nothing outside it can be fetched."
  [document]
  (vec (sort (remove #(resolves? document %) (distinct (refs document))))))

;; --- the document validates ----------------------------------------

(def ^:private oas-schema
  "https://spec.openapis.org/oas/3.1/schema-base/2025-02-13")

(def ^:private vendored-schemas
  "Every schema the OpenAPI 3.1 schema reaches, against its copy under
  `test-resources/openapi/`. The registry resolves through this map
  alone, so a URI missing from it fails the run rather than being
  fetched."
  {"https://spec.openapis.org/oas/3.1/schema-base/2025-02-13"
   "classpath:openapi/oas-3.1-schema-base-2025-02-13.json"
   "https://spec.openapis.org/oas/3.1/schema/2025-02-13"
   "classpath:openapi/oas-3.1-schema-2025-02-13.json"
   "https://spec.openapis.org/oas/3.1/dialect/2024-11-10"
   "classpath:openapi/oas-3.1-dialect-2024-11-10.json"
   "https://spec.openapis.org/oas/3.1/meta/2024-11-10"
   "classpath:openapi/oas-3.1-meta-2024-11-10.json"
   "https://json-schema.org/draft/2020-12/schema"
   "classpath:openapi/json-schema-2020-12.json"
   "https://json-schema.org/draft/2020-12/meta/core"
   "classpath:openapi/json-schema-2020-12-meta-core.json"
   "https://json-schema.org/draft/2020-12/meta/applicator"
   "classpath:openapi/json-schema-2020-12-meta-applicator.json"
   "https://json-schema.org/draft/2020-12/meta/unevaluated"
   "classpath:openapi/json-schema-2020-12-meta-unevaluated.json"
   "https://json-schema.org/draft/2020-12/meta/validation"
   "classpath:openapi/json-schema-2020-12-meta-validation.json"
   "https://json-schema.org/draft/2020-12/meta/meta-data"
   "classpath:openapi/json-schema-2020-12-meta-meta-data.json"
   "https://json-schema.org/draft/2020-12/meta/format-annotation"
   "classpath:openapi/json-schema-2020-12-meta-format-annotation.json"
   "https://json-schema.org/draft/2020-12/meta/content"
   "classpath:openapi/json-schema-2020-12-meta-content.json"})

(defn- registry
  "A registry over the vendored copies that fetches nothing:
  `fetchRemoteResources` is off, so a URI `vendored-schemas` does not
  name fails the run rather than reaching the network."
  ^SchemaRegistry []
  (-> (SchemaRegistry/builder)
      (.schemaLoader
       (reify
        Consumer
          (accept [_ builder]
            (doto ^SchemaLoader$Builder builder
              (.fetchRemoteResources false)
              (.schemaIdResolvers
               (reify
                Consumer
                  (accept [_ resolvers]
                    (.mappings ^SchemaIdResolvers$Builder resolvers
                               vendored-schemas))))))))
      (.build)))

(defn- errors
  [document-json]
  (.validate (.getSchema (registry) (SchemaLocation/of oas-schema))
             ^String document-json
             InputFormat/JSON))

(def ^:private standing-gaps
  "What the exported document already fails, keyed by class and valued
  by the number of validation errors that class accounts for.

  - `:response-missing-description` — a Response Object must carry
    `description`. `schema/ErrorResponse` sets one for every error
    response in the document, and the webhook routes set one on each
    of their own; the remainder are the 2xx entries of the route
    families that have not set theirs yet.
  - `:responses-cascade`, `:paths-cascade` — `unevaluatedProperties`
    reported against the Responses Object and against `paths` for each
    child the line above failed. Consequences, not faults of their
    own.
  - `:example-payload-not-under-value` — an Example Object carries
    `summary`, `description`, `value` and `externalValue`; seven of
    the document's examples put the payload's own keys there instead.
  - `:operation-stray-key` — one operation carries `headers`, which
    belongs to a Response Object.

  ADR-0014 wants every one of these at zero. Closing them changes the
  exported document, so it belongs to the work that owns the routes
  rather than to this harness. Lower a number as its class shrinks; a
  route family that lands without closing its own share of a class
  raises one, which is a gap the family owns rather than a licence the
  harness grants."
  {:response-missing-description 71
   :responses-cascade 71
   :paths-cascade 59
   :example-payload-not-under-value 31
   :operation-stray-key 1})

(defn- classify
  [^com.networknt.schema.Error error]
  (let [at (str (.getInstanceLocation error))
        rule (.getKeyword error)
        unevaluated? (= "unevaluatedProperties" rule)]
    (cond
     (and (= "required" rule)
          (= "description" (.getProperty error))
          (re-matches #"/paths/[^/]+/[^/]+/responses/[^/]+" at))
     :response-missing-description

     (and unevaluated? (re-matches #"/paths/[^/]+/[^/]+/responses" at))
     :responses-cascade

     (and unevaluated? (= "/paths" at))
     :paths-cascade

     (and unevaluated? (re-matches #"/components/examples/[^/]+" at))
     :example-payload-not-under-value

     (and unevaluated? (re-matches #"/paths/[^/]+/[^/]+" at))
     :operation-stray-key

     :else
     :unrecorded)))

(defn- describe
  [^com.networknt.schema.Error error]
  (str (.getInstanceLocation error) " — " (.getMessage error)))

(defn- grown
  "The recorded classes carrying more errors than `standing-gaps`
  allows, each against what it was and what it is now."
  [by-class]
  (into {}
        (keep (fn [[class standing]]
                (let [now (count (get by-class class))]
                  (when (< standing now)
                    [class {:standing standing :now now}]))))
        standing-gaps))

;; --- the tests -----------------------------------------------------

(deftest exports-the-document-test
  (let [{:keys [status body]} @exported]
    (is (= 200 status) "an error body must not pass for the document")
    (nom-test> [document (json/read-str body)
                _ (is (= "3.1.0" (get document "openapi")))
                _ (is (seq (get document "paths")))])))

(deftest every-ref-resolves-test
  (nom-test> [document (json/read-str (:body @exported))
              _ (is (= [] (unresolved document))
                    "a $ref naming nothing in the document")]))

(deftest validates-against-the-openapi-schema-test
  (let [by-class (group-by classify (errors (:body @exported)))]
    (testing "no departure outside the recorded ones"
      (is (= [] (mapv describe (:unrecorded by-class)))))
    (testing "no recorded class grows" (is (= {} (grown by-class))))))

;; --- parameters the validation cannot see --------------------------

(def ^:private operation-methods ["get" "post" "put" "patch" "delete"])

(defn- operations
  "Each operation in `document` as `\"METHOD path\"` against its
  Operation Object."
  [document]
  (into {}
        (for [[path item] (get document "paths")
              method operation-methods
              :let [operation (get item method)]
              :when operation]
          [(str (str/upper-case method) " " path) operation])))

(defn- parameters
  "The `[in name]` of each parameter `operation` lists, a `$ref` resolved
  against the document's components."
  [document operation]
  (into #{}
        (map (fn [parameter]
               (let [resolved (if-let [ref (get parameter "$ref")]
                                (get-in document
                                        ["components" "parameters"
                                         (last (str/split ref #"/"))])
                                parameter)]
                 [(get resolved "in") (get resolved "name")])))
        (get operation "parameters")))

(def ^:private baseline
  "Every operation that listed a parameter at `4b5cc4a5`, against the
  `[in name]` of each."
  (delay (edn/read-string (slurp (io/resource
                                  "openapi/parameters-4b5cc4a5.edn")))))

(deftest every-path-variable-has-a-path-parameter-test
  (nom-test> [document (json/read-str (:body @exported))
              ops (operations document)
              _ (is (seq ops))
              _ (doseq [[op operation] (sort ops)
                        :let [path (second (str/split op #" " 2))
                              variables (set (map second
                                                  (re-seq #"\{([^}]+)\}" path)))
                              listed (parameters document operation)]]
                  (testing op
                    (is (= #{}
                           (into #{}
                                 (remove #(contains? listed ["path" %]))
                                 variables))
                        "a path variable with no in: path parameter")))]))

(deftest every-earlier-parameter-is-still-listed-test
  (nom-test> [document (json/read-str (:body @exported))
              ops (operations document)
              _ (is (seq @baseline))
              _ (doseq [[op earlier] (sort @baseline)]
                  (testing op
                    (is (contains? ops op) "the operation still exists")
                    (is (= #{}
                           (into #{}
                                 (remove (parameters document (get ops op)))
                                 earlier))
                        "a parameter listed at 4b5cc4a5 and dropped since")))]))

(deftest create-bank-documents-the-owner-invitation-test
  (nom-test> [document (json/read-str (:body @exported))
              schemas (get-in document ["components" "schemas"])
              request (get schemas "CreateBankRequest")
              response (get schemas "CreateBankResponse")
              _ (testing "the request takes an optional owner-email"
                  (is (= "#/components/schemas/EmailAddress"
                         (get-in request ["properties" "owner-email" "$ref"])))
                  (is (not-any? #{"owner-email"} (get request "required"))))
              _ (testing "the response answers the optional owner invitation"
                  (is (= "#/components/schemas/InvitationWithToken"
                         (get-in response
                                 ["properties" "owner-invitation" "$ref"])))
                  (is (not-any? #{"owner-invitation"}
                                (get response "required"))))]))
