(ns com.repldriven.queenswood.api.openapi-test
  "The document builds the way `export-spec` builds it, without booting
  a system, and says what it says here: every parameter an operation
  listed at `4b5cc4a5`, recorded in
  `test-resources/openapi/parameters-4b5cc4a5.edn`, it still lists, and
  a response's own links reach it. Whether it is valid OpenAPI 3.2, its
  `$ref`s resolve and its path variables have parameters is for
  `just openapi-lint`."
  (:require
    [com.repldriven.queenswood.api.api :as api]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.test-system.interface :refer [nom-test>]]

    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]))

(def ^:private exported
  "The document as `export-spec` produces it: `api/app` with no
  interceptors and a GET of `/openapi.json`. A delay, so the tests
  below build it once between them."
  (delay (let [handler (api/app {:interceptors []})
               {:keys [status body]} (handler {:request-method :get
                                               :uri "/openapi.json"})]
           {:status status :body (slurp body)})))

;; --- the document --------------------------------------------------

(deftest exports-the-document-test
  (let [{:keys [status body]} @exported]
    (is (= 200 status) "an error body must not pass for the document")
    (nom-test> [document (json/read-str body)
                _ (is (= "3.2.0" (get document "openapi")))
                _ (is (seq (get document "paths")))])))

;; --- parameters ------------------------------------------------------

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
                  (is (= "#/components/schemas/Invitation"
                         (get-in response
                                 ["properties" "owner-invitation" "$ref"])))
                  (is (not-any? #{"owner-invitation"}
                                (get response "required"))))]))

(deftest responses-carry-their-own-links-test
  (nom-test> [document (json/read-str (:body @exported))
              _ (is (contains? (get-in document
                                       ["paths" "/v1/cash-account-products"
                                        "post" "responses" "201" "links"])
                               "GetVersion"))]))
