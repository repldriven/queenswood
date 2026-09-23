(ns com.repldriven.queenswood.onfido-simulator.api
  (:require
    [com.repldriven.queenswood.onfido-simulator.schema :as schema]

    [com.repldriven.queenswood.onfido-simulator.applicants.components :as
     applicants.components]
    [com.repldriven.queenswood.onfido-simulator.applicants.examples :as
     applicants.examples]
    [com.repldriven.queenswood.onfido-simulator.applicants.routes :as
     applicants]
    [com.repldriven.queenswood.onfido-simulator.checks.components :as
     checks.components]
    [com.repldriven.queenswood.onfido-simulator.checks.examples :as
     checks.examples]
    [com.repldriven.queenswood.onfido-simulator.checks.routes :as checks]
    [com.repldriven.queenswood.onfido-simulator.webhooks.components :as
     webhooks.components]
    [com.repldriven.queenswood.onfido-simulator.webhooks.examples :as
     webhooks.examples]
    [com.repldriven.queenswood.onfido-simulator.webhooks.routes :as webhooks]

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

(defn- examples-registry
  [vars]
  (schema/examples-registry vars))

(def ^:private applicants-examples
  (examples-registry [#'applicants.examples/CreateApplicantRequest
                      #'applicants.examples/Applicant]))

(def ^:private checks-examples
  (examples-registry [#'checks.examples/CreateCheckRequest
                      #'checks.examples/Check]))

(def ^:private webhooks-examples
  (examples-registry [#'webhooks.examples/RegisterWebhookRequest
                      #'webhooks.examples/Webhook
                      #'webhooks.examples/WebhookList]))

(def ^:private coercion
  (malli-coercion/create
   {:transformers {:body {:default (->provider (mt/json-transformer))}
                   :string {:default (->provider (mt/string-transformer))}
                   :response {:default (->provider nil)}}
    :options {:registry (merge (m/default-schemas)
                               {"ErrorResponse" schema/ErrorResponseSchema}
                               applicants.components/registry
                               checks.components/registry
                               webhooks.components/registry
                               onfido-webhook/component-registry)}}))

(defn- routes
  [ctx]
  (into
   (server/health-routes ctx)
   [["/openapi.json"
     {:get {:no-doc true
            :openapi
            {:info {:title "Onfido Simulator"
                    :description "Simulates the Onfido IDV API for testing"
                    :version "0.0.4"}
             :components {:examples (merge applicants-examples
                                           checks-examples
                                           webhooks-examples
                                           onfido-webhook/example-registry)}}
            :handler (server/standard-openapi-handler)}}]
    (into ["" {:interceptors (:interceptors ctx)}]
          (concat applicants/routes
                  checks/routes
                  webhooks/routes))]))

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
