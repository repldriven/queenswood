(ns com.repldriven.queenswood.uk-companies-house-simulator.api
  (:require
    [com.repldriven.queenswood.uk-companies-house-simulator.schema :as schema]

    [com.repldriven.queenswood.uk-companies-house-simulator.companies.components
     :as companies.components]
    [com.repldriven.queenswood.uk-companies-house-simulator.companies.examples
     :as companies.examples]
    [com.repldriven.queenswood.uk-companies-house-simulator.companies.routes :as
     companies]

    [com.repldriven.mono.server.interface :as server]

    [malli.core :as m]
    [malli.transform :as mt]
    [reitit.coercion.malli :as malli-coercion]
    [reitit.http :as http]
    [reitit.ring :as ring]))

(defn- ->provider
  [base-transformer]
  (reify
   malli-coercion/TransformationProvider
     (-transformer [_ {:keys [strip-extra-keys default-values]}]
       (mt/transformer
        (when strip-extra-keys
          (mt/strip-extra-keys-transformer))
        base-transformer
        (when default-values
          (mt/default-value-transformer))))))

(defn- examples-registry
  [vars]
  (schema/examples-registry vars))

(def ^:private companies-examples
  (examples-registry [#'companies.examples/Company
                      #'companies.examples/RegisteredOfficeAddress
                      #'companies.examples/ErrorResponse]))

(def ^:private coercion
  (malli-coercion/create
   {:transformers {:body {:default (->provider (mt/json-transformer))}
                   :string {:default (->provider (mt/string-transformer))}
                   :response {:default (->provider nil)}}
    :options {:registry (merge (m/default-schemas)
                               {"ErrorResponse" schema/ErrorResponseSchema}
                               companies.components/registry)}}))

(defn- routes
  [ctx]
  (into
   (server/health-routes ctx)
   [["/openapi.json"
     {:get {:no-doc true
            :openapi
            {:info {:title "UK Companies House Simulator"
                    :description
                    "Simulates the UK Companies House API for testing"
                    :version "0.0.6"}
             :components {:examples (merge companies-examples)}}
            :handler (server/standard-openapi-handler)}}]
    (into ["" {:interceptors (:interceptors ctx)}]
          (concat companies/routes))]))

(defn app
  [ctx]
  (http/ring-handler
   (http/router (routes ctx)
                (assoc-in server/standard-router-data
                 [:data :coercion]
                 coercion))
   (ring/routes (server/standard-openapi-ui-handler)
                (server/standard-default-handler))
   server/standard-executor))
