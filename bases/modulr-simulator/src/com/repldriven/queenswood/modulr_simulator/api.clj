(ns com.repldriven.queenswood.modulr-simulator.api
  (:require
    [com.repldriven.queenswood.modulr-simulator.accounts.routes :as accounts]
    [com.repldriven.queenswood.modulr-simulator.components :as components]
    [com.repldriven.queenswood.modulr-simulator.name-check.routes
     :as name-check]
    [com.repldriven.queenswood.modulr-simulator.notifications.routes
     :as notifications]
    [com.repldriven.queenswood.modulr-simulator.payments.routes :as payments]
    [com.repldriven.queenswood.modulr-simulator.simulate.routes :as simulate]

    [com.repldriven.queenswood.modulr-webhook.interface :as modulr-webhook]

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
                               components/registry
                               modulr-webhook/component-registry)}}))

(defn- routes
  [ctx]
  (into
   (server/health-routes ctx)
   [["/openapi.json"
     {:get {:no-doc true
            :openapi {:info {:title "Modulr Simulator"
                             :description
                             "Simulates the Modulr payments API for testing"
                             :version "0.0.6"}
                      :components {:examples
                                   modulr-webhook/example-registry}}
            :handler (server/standard-openapi-handler)}}]
    (into ["" {:interceptors (:interceptors ctx)}]
          (concat accounts/routes
                  payments/routes
                  notifications/routes
                  name-check/routes
                  simulate/routes))]))

(defn app
  [ctx]
  (http/ring-handler
   (http/router
    (routes ctx)
    (assoc-in server/standard-router-data [:data :coercion] coercion))
   (ring/routes (server/standard-openapi-ui-handler)
                (server/standard-default-handler))
   server/standard-executor))
