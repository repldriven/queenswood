(ns com.repldriven.queenswood.clearbank-adapter.api
  (:require
    [com.repldriven.queenswood.clearbank-adapter.cop.components :as
     cop.components]
    [com.repldriven.queenswood.clearbank-adapter.cop.examples :as
     cop.examples]
    [com.repldriven.queenswood.clearbank-adapter.cop.routes :as cop]
    [com.repldriven.queenswood.clearbank-adapter.webhook.routes
     :as webhook]

    [com.repldriven.queenswood.clearbank-webhook.interface
     :as clearbank-webhook]

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

(def ^:private coercion
  (malli-coercion/create
   {:transformers {:body {:default (->provider (mt/json-transformer))}
                   :string {:default (->provider (mt/string-transformer))}
                   :response {:default (->provider nil)}}
    :options {:registry (merge (m/default-schemas)
                               clearbank-webhook/component-registry
                               cop.components/registry)}}))

(defn- routes
  [ctx]
  (into
   (server/health-routes ctx)
   [["/openapi.json"
     {:get {:no-doc true
            :openapi
            {:info {:title "ClearBank Adapter"
                    :description
                    "Adapts between Queenswood and ClearBank APIs"
                    :version "0.0.5"}
             :components
             {:examples (merge cop.examples/registry
                               clearbank-webhook/example-registry)}}
            :handler (server/standard-openapi-handler)}}]
    (into ["" {:interceptors (:interceptors ctx)}]
          (concat cop/routes webhook/routes))]))

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
