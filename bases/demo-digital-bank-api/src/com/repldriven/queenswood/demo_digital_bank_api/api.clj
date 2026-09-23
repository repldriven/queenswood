(ns com.repldriven.queenswood.demo-digital-bank-api.api
  (:require
    [com.repldriven.queenswood.demo-digital-bank-api.account.components :as
     account.components]
    [com.repldriven.queenswood.demo-digital-bank-api.account.routes :as account]
    [com.repldriven.queenswood.demo-digital-bank-api.auth :as auth]
    [com.repldriven.queenswood.demo-digital-bank-api.errors :as errors]
    [com.repldriven.queenswood.demo-digital-bank-api.home.components :as
     home.components]
    [com.repldriven.queenswood.demo-digital-bank-api.home.routes :as home]
    [com.repldriven.queenswood.demo-digital-bank-api.payment.components :as
     payment.components]
    [com.repldriven.queenswood.demo-digital-bank-api.payment.routes :as payment]
    [com.repldriven.queenswood.demo-digital-bank-api.platform.components :as
     platform.components]
    [com.repldriven.queenswood.demo-digital-bank-api.platform.interceptors :as
     platform.interceptors]
    [com.repldriven.queenswood.demo-digital-bank-api.platform.routes :as
     platform]
    [com.repldriven.queenswood.demo-digital-bank-api.session.components :as
     session.components]
    [com.repldriven.queenswood.demo-digital-bank-api.session.routes :as session]
    [com.repldriven.queenswood.demo-digital-bank-api.shared.components :as
     shared.components]
    [com.repldriven.queenswood.demo-digital-bank-api.sign-up.components :as
     sign-up.components]
    [com.repldriven.queenswood.demo-digital-bank-api.sign-up.routes :as sign-up]

    [com.repldriven.mono.server.interface :as server]

    [malli.core :as m]
    [malli.json-schema :as mjs]
    [malli.transform :as mt]
    [reitit.coercion.malli :as malli-coercion]
    [reitit.http :as http]
    [reitit.ring :as ring]))

(defn- ->provider
  [base-transformer]
  (reify
   malli-coercion/TransformationProvider
     (-transformer [_ {:keys [default-values]}]
       (mt/transformer base-transformer
                       (when default-values
                         (mt/default-value-transformer))))))

(def ^:private schema-registry
  (merge (m/default-schemas)
         shared.components/registry
         sign-up.components/registry
         session.components/registry
         home.components/registry
         payment.components/registry
         account.components/registry
         platform.components/registry))

(def ^:private coercion
  (malli-coercion/create
   {:transformers {:body {:default (->provider (mt/json-transformer))}
                   :string {:default (->provider (mt/string-transformer))}
                   :response {:default (->provider nil)}}
    :strip-extra-keys false
    :options {:registry schema-registry}}))

(def ^:private documented-schemas
  "The JSON Schema of the shapes no route coerces — the stream's events
  — keyed as `components/schemas` keys them.
  Reitit fills that key from the route schemas it transforms and then
  replaces it wholesale, so a schema only a `$ref` in an `:openapi`
  block names reaches the document after its handler has run."
  (delay (into {}
               (map (fn [name]
                      (:definitions (mjs/transform [:ref name]
                                                   {:registry schema-registry
                                                    ::mjs/definitions-path
                                                    "#/components/schemas/"}))))
               ["Notification"])))

(defn- openapi-handler
  "The standard handler, with the documented schemas merged under the
  ones reitit collected, so every `$ref` resolves."
  []
  (let [handler (server/standard-openapi-handler)
        with-documented (fn [response]
                          (update-in response
                                     [:body :components :schemas]
                                     (fn [schemas]
                                       (merge @documented-schemas schemas))))]
    (fn
      ([request] (with-documented (handler request)))
      ([request respond raise]
       (handler request (comp respond with-documented) raise)))))

(defn- routes
  "Every route under one group: the system's interceptors, then the
  session's resolution and the gate, which compiles to nothing for a
  route that declares no `sessionAuth`."
  [ctx]
  (into (server/health-routes ctx)
        [["/openapi.json"
          {:get {:no-doc true
                 :openapi {:info {:title "Demo digital bank"
                                  :description
                                  "The bank behind the demo digital bank's app"
                                  :version "0.0.4"}
                           :components
                           {:securitySchemes
                            {"sessionAuth" {:type :http
                                            :scheme :bearer
                                            :description
                                            "A session the bank minted"}}}}
                 :handler (openapi-handler)}}]
         (into [""
                {:interceptors (into (vec (:interceptors ctx))
                                     [auth/credential->customer
                                      server/require-scopes])
                 :unauthorized errors/unauthenticated-response}]
               (concat sign-up/routes
                       session/routes
                       platform/routes
                       home/routes
                       payment/routes
                       account/routes))]))

(defn app
  [ctx]
  (-> (http/ring-handler (http/router
                          (routes ctx)
                          (-> server/standard-router-data
                              (assoc-in [:data :coercion] coercion)
                              (update-in [:data :interceptors]
                                         (fn [chain]
                                           (into [platform.interceptors/raw-body
                                                  server/credential]
                                                 chain)))))
                         (ring/routes (server/standard-openapi-ui-handler)
                                      (server/standard-default-handler))
                         server/standard-executor)
      (server/wrap-cors (:cors ctx))))
