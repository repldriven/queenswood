(ns com.repldriven.queenswood.zyphe-simulator.api
  (:require
    [com.repldriven.queenswood.zyphe-simulator.schema :as schema]

    [com.repldriven.queenswood.zyphe-simulator.verification-requests.components
     :as verification-requests.components]
    [com.repldriven.queenswood.zyphe-simulator.verification-requests.examples
     :as verification-requests.examples]
    [com.repldriven.queenswood.zyphe-simulator.verification-requests.routes :as
     verification-requests]

    [com.repldriven.queenswood.zyphe-webhook.interface :as zyphe-webhook]

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

(def ^:private verification-requests-examples
  (schema/examples-registry
   [#'verification-requests.examples/Credential
    #'verification-requests.examples/SessionWebhookRequest
    #'verification-requests.examples/CreateVerificationRequest
    #'verification-requests.examples/VerificationRequest
    #'verification-requests.examples/SessionWebhook
    #'verification-requests.examples/CreateVerificationRequestResponse
    #'verification-requests.examples/Decision]))

(def ^:private coercion
  (malli-coercion/create
   {:transformers {:body {:default (->provider (mt/json-transformer))}
                   :string {:default (->provider (mt/string-transformer))}
                   :response {:default (->provider nil)}}
    :options {:registry (merge (m/default-schemas)
                               verification-requests.components/registry
                               zyphe-webhook/component-registry)}}))

(defn- routes
  [ctx]
  (into
   (server/health-routes ctx)
   [["/openapi.json"
     {:get {:no-doc true
            :openapi
            {:info {:title "Zyphe Simulator"
                    :description
                    "Simulates the Zyphe verification API for testing"
                    :version "0.0.6"}
             :components {:examples (merge verification-requests-examples
                                           zyphe-webhook/example-registry)}}
            :handler (server/standard-openapi-handler)}}]
    (into ["" {:interceptors (:interceptors ctx)}]
          verification-requests/routes)]))

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
