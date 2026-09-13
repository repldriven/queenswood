(ns com.repldriven.queenswood.api.webhook.routes-test
  "The webhook surface as the router and the document carry it: every
  route REQ-025 names exists at the path and method it documents
  (AC-18), and the signing secret is declared on the registration and
  rotation shapes and on nothing else (AC-17).

  No system is booted. The router is compiled from an empty
  interceptor context, and the document is built the way `export-spec`
  builds it."
  (:require
    [com.repldriven.queenswood.api.api :as api]

    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.test-system.interface :refer [nom-test>]]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]

    [reitit.core :as r]))

(def ^:private base "/v1/webhook-endpoints")

(def ^:private required-routes
  "Every route REQ-025 names, as the `[method template]` pair
  `reitit.core/routes` reports."
  #{[:get base] [:post base] [:get (str base "/{endpoint-id}")]
    [:put (str base "/{endpoint-id}")] [:delete (str base "/{endpoint-id}")]
    [:post (str base "/{endpoint-id}/enable")]
    [:post (str base "/{endpoint-id}/disable")]
    [:post (str base "/{endpoint-id}/rotate-secret")]
    [:post (str base "/{endpoint-id}/test-notification")]
    [:get (str base "/{endpoint-id}/deliveries")]
    [:post (str base "/{endpoint-id}/deliveries/{delivery-id}/resend")]
    [:post (str base "/{endpoint-id}/resend")]})

(def ^:private secret-bearing
  "The only two shapes that may declare the secret: the answer to a
  registration and the answer to a rotation."
  #{"WebhookEndpointRegistration" "WebhookEndpointSecretRotation"})

(def ^:private exported
  (delay (let [handler (api/app {:interceptors []})
               {:keys [body]} (handler {:request-method :get
                                        :uri "/openapi.json"})]
           (slurp body))))

(defn- compiled
  []
  (into {}
        (for [[template data] (r/routes (api/router {:interceptors []}))
              method [:get :post :put :patch :delete]
              :let [method-data (get data method)]
              :when method-data]
          [[method template] method-data])))

(deftest every-route-the-requirements-name-is-compiled-test
  (let [routes (compiled)]
    (doseq [route (sort required-routes)]
      (testing (str route)
        (is (contains? routes route)
            "exists at the path and method REQ-025 documents")
        (is (fn? (:handler (get routes route))) "and answers with a handler")))
    (testing "and the base carries no webhook route beyond them"
      (is (= required-routes
             (into #{}
                   (filter (fn [[_ template]] (str/starts-with? template base)))
                   (keys routes)))))))

(defn- response-ref
  [document template method status]
  (get-in document
          ["paths" template method "responses" status "content"
           "application/json" "schema" "$ref"]))

(deftest only-registration-and-rotation-carry-the-secret-test
  (nom-test> [document (json/read-str @exported)
              schemas (get-in document ["components" "schemas"])
              declaring (into #{}
                              (keep (fn [[schema-name schema]]
                                      (when (and (map? schema)
                                                 (contains? (get schema
                                                                 "properties")
                                                            "secret"))
                                        schema-name)))
                              schemas)
              _ (testing "no shape but the two answers declares the secret"
                  (is (= secret-bearing declaring)))
              _ (testing
                  "and the read shape declares none of the stored secrets"
                  (let [properties (get-in schemas
                                           ["WebhookEndpoint" "properties"])]
                    (is (seq properties))
                    (is (empty? (filter #{"secret" "previous-secret"
                                          "previous-secret-expires-at"
                                          "idempotency-key"
                                          "rotation-idempotency-key"}
                                        (keys properties))))))]))

(deftest the-read-routes-answer-with-the-shape-that-hides-it-test
  (nom-test>
    [document (json/read-str @exported)
     _
     (testing
       "registration and rotation answer with the secret-bearing
               shapes"
       (is (= "#/components/schemas/WebhookEndpointRegistration"
              (response-ref document base "post" "201")))
       (is (= "#/components/schemas/WebhookEndpointSecretRotation"
              (response-ref document
                            (str base "/{endpoint-id}/rotate-secret")
                            "post"
                            "200"))))
     _ (testing "get and list answer with the shape that declares none"
         (is (=
              "#/components/schemas/WebhookEndpoint"
              (response-ref document (str base "/{endpoint-id}") "get" "200")))
         (is (= "#/components/schemas/WebhookEndpointList"
                (response-ref document base "get" "200"))))]))

(deftest the-history-documents-the-filters-it-takes-test
  (nom-test>
    [document (json/read-str @exported)
     parameters (get-in document
                        ["paths" (str base "/{endpoint-id}/deliveries")
                         "get"
                         "parameters"])
     referenced (into #{} (map #(get % "$ref")) parameters)
     _ (testing
         "the route names the endpoint, the filter, the page and the bank"
         (is (= #{"#/components/parameters/EndpointId"
                  "#/components/parameters/DeliveryFilterQuery"
                  "#/components/parameters/PageQuery"
                  "#/components/parameters/BankIdHeader"}
                referenced)))
     filters (get-in document
                     ["components" "parameters" "DeliveryFilterQuery"
                      "schema"
                      "properties"])
     _ (testing "and the filter takes a kind, an outcome and a window"
         (is (= #{"kind" "outcome" "from" "to"} (set (keys filters)))))
     _
     (testing
       "whose outcome refers to the status component rather
                        than restating its values, so a fifth status
                        cannot be advertised in one place and not the
                        other"
       (is (= {"$ref" "#/components/schemas/WebhookDeliveryStatus"}
              (get filters "outcome"))))]))
