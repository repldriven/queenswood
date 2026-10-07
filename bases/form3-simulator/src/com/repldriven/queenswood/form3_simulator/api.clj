(ns com.repldriven.queenswood.form3-simulator.api
  (:require
    [com.repldriven.queenswood.form3-simulator.accounts.routes :as accounts]
    [com.repldriven.queenswood.form3-simulator.components :as components]
    [com.repldriven.queenswood.form3-simulator.name-verification.routes
     :as name-verification]
    [com.repldriven.queenswood.form3-simulator.payments.routes :as payments]
    [com.repldriven.queenswood.form3-simulator.simulate.routes :as simulate]
    [com.repldriven.queenswood.form3-simulator.subscriptions.routes
     :as subscriptions]

    [com.repldriven.queenswood.form3-webhook.interface :as form3-webhook]

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
                               form3-webhook/component-registry)}}))

(defn- routes
  [ctx]
  (into
   (server/health-routes ctx)
   [["/openapi.json"
     {:get {:no-doc true
            :openapi {:info {:title "Form3 Simulator"
                             :description
                             "Simulates the Form3 payments API for testing"
                             :version "0.0.8"}
                      :components {:examples
                                   form3-webhook/example-registry}}
            :handler (server/standard-openapi-handler)}}]
    (into ["" {:interceptors (:interceptors ctx)}]
          (concat [(into ["/v1"]
                         (concat accounts/routes
                                 payments/routes
                                 subscriptions/routes
                                 name-verification/routes))]
                  simulate/routes))]))

(defn app
  [ctx]
  (http/ring-handler
   (http/router (routes ctx)
                (-> server/standard-router-data
                    (assoc-in [:data :coercion] coercion)
                    (update-in [:data :interceptors]
                               (fn [chain]
                                 (into [form3-webhook/raw-body] chain)))))
   (ring/routes (server/standard-openapi-ui-handler)
                (server/standard-default-handler))
   server/standard-executor))
