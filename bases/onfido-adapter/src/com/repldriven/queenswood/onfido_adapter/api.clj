(ns com.repldriven.queenswood.onfido-adapter.api
  (:require
    [com.repldriven.queenswood.onfido-adapter.webhook.routes :as webhook]

    [com.repldriven.queenswood.onfido-webhook.interface :as onfido-webhook]

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
                               onfido-webhook/component-registry)}}))

(defn- routes
  [ctx]
  (into
   (server/health-routes ctx)
   [["/openapi.json"
     {:get {:no-doc true
            :openapi {:info {:title "Onfido Adapter"
                             :description
                             "Receives Onfido check.completed webhooks"
                             :version "0.0.6"}
                      :components
                      {:examples onfido-webhook/example-registry}}
            :handler (server/standard-openapi-handler)}}]
    (into ["" {:interceptors (:interceptors ctx)}] webhook/routes)]))

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
