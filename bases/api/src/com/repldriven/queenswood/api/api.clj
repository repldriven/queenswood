(ns com.repldriven.queenswood.api.api
  (:require
    [com.repldriven.queenswood.api.auth :as auth]
    [com.repldriven.queenswood.api.examples :as examples]
    [com.repldriven.queenswood.api.schema :as schema]

    [com.repldriven.queenswood.api.balance.components :as balance.components]
    [com.repldriven.queenswood.api.balance.examples :as balance.examples]
    [com.repldriven.queenswood.api.balance.routes :as balance]
    [com.repldriven.queenswood.api.bank.components :as bank.components]
    [com.repldriven.queenswood.api.bank.examples :as bank.examples]
    [com.repldriven.queenswood.api.bank.routes :as bank]
    [com.repldriven.queenswood.api.cash-account.components :as
     cash-account.components]
    [com.repldriven.queenswood.api.cash-account.examples :as
     cash-account.examples]
    [com.repldriven.queenswood.api.cash-account.routes :as cash-account]
    [com.repldriven.queenswood.api.cash-account-migration.components :as
     cash-account-migration.components]
    [com.repldriven.queenswood.api.cash-account-migration.examples :as
     cash-account-migration.examples]
    [com.repldriven.queenswood.api.cash-account-migration.routes :as
     cash-account-migration]
    [com.repldriven.queenswood.api.cash-account-product.components :as
     cash-account-product.components]
    [com.repldriven.queenswood.api.cash-account-product.examples :as
     cash-account-product.examples]
    [com.repldriven.queenswood.api.cash-account-product.routes :as
     cash-account-product]
    [com.repldriven.queenswood.api.companies.components :as
     companies.components]
    [com.repldriven.queenswood.api.companies.examples :as companies.examples]
    [com.repldriven.queenswood.api.companies.routes :as companies]
    [com.repldriven.queenswood.api.jobs.components :as jobs.components]
    [com.repldriven.queenswood.api.jobs.examples :as jobs.examples]
    [com.repldriven.queenswood.api.jobs.routes :as jobs]
    [com.repldriven.queenswood.api.ledger-account.components :as
     ledger-account.components]
    [com.repldriven.queenswood.api.ledger-account.examples :as
     ledger-account.examples]
    [com.repldriven.queenswood.api.ledger-account.routes :as ledger-account]
    [com.repldriven.queenswood.api.me.components :as me.components]
    [com.repldriven.queenswood.api.me.examples :as me.examples]
    [com.repldriven.queenswood.api.me.routes :as me]
    [com.repldriven.queenswood.api.oauth.components :as oauth.components]
    [com.repldriven.queenswood.api.oauth.examples :as oauth.examples]
    [com.repldriven.queenswood.api.oauth.routes :as oauth]
    [com.repldriven.queenswood.api.onboarding.components :as
     onboarding.components]
    [com.repldriven.queenswood.api.onboarding.examples :as onboarding.examples]
    [com.repldriven.queenswood.api.onboarding.routes :as onboarding]
    [com.repldriven.queenswood.api.party.components :as party.components]
    [com.repldriven.queenswood.api.party.examples :as party.examples]
    [com.repldriven.queenswood.api.party.routes :as party]
    [com.repldriven.queenswood.api.payee-check.components :as
     payee-check.components]
    [com.repldriven.queenswood.api.payee-check.examples :as
     payee-check.examples]
    [com.repldriven.queenswood.api.payee-check.routes :as payee-check]
    [com.repldriven.queenswood.api.payment.components :as payment.components]
    [com.repldriven.queenswood.api.payment.examples :as payment.examples]
    [com.repldriven.queenswood.api.payment.routes :as payment]
    [com.repldriven.queenswood.api.policy.components :as policy.components]
    [com.repldriven.queenswood.api.policy.examples :as policy.examples]
    [com.repldriven.queenswood.api.policy.routes :as policy]
    [com.repldriven.queenswood.api.shared.components :as shared.components]
    [com.repldriven.queenswood.api.shared.interceptors :as shared.interceptors]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]
    [com.repldriven.queenswood.api.simulate.components :as simulate.components]
    [com.repldriven.queenswood.api.simulate.examples :as simulate.examples]
    [com.repldriven.queenswood.api.simulate.routes :as simulate]
    [com.repldriven.queenswood.api.tier.components :as tier.components]
    [com.repldriven.queenswood.api.tier.examples :as tier.examples]
    [com.repldriven.queenswood.api.tier.routes :as tier]
    [com.repldriven.queenswood.api.transaction.components :as
     transaction.components]

    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.telemetry.interface :as telemetry]

    [clojure.string :as str]

    [malli.core :as m]
    [malli.transform :as mt]
    [reitit.coercion.malli :as malli-coercion]
    [reitit.http :as http]
    [reitit.ring :as ring]))

(def ^:private api-transformer
  "Transformer for :decode/api and :encode/api properties
  on malli schemas. Composed with the base transformers
  to coerce API-friendly enum values to/from internal
  prefixed keywords."
  (mt/transformer {:name :api}))

(defn- ->provider
  "Creates a reitit TransformationProvider that composes
  base-transformer with api-transformer."
  [base-transformer]
  (reify
   malli-coercion/TransformationProvider
     (-transformer [_ {:keys [strip-extra-keys default-values]}]
       (mt/transformer (when strip-extra-keys
                         (mt/strip-extra-keys-transformer))
                       base-transformer
                       api-transformer
                       (when default-values (mt/default-value-transformer))))))

(def ^:private coercion
  (malli-coercion/create
   {:transformers {:body {:default (->provider (mt/json-transformer))}
                   :string {:default (->provider (mt/string-transformer))}
                   :response {:default (->provider nil)}}
    ;; Keep `:compile mu/closed-schema` (reitit default) effective by
    ;; turning off `:strip-extra-keys`; otherwise the strip transformer
    ;; removes unknown keys before validation runs, so closed maps
    ;; never reject them. We want 400s for unexpected fields on both
    ;; query-params and request bodies.
    :strip-extra-keys false
    :options {:registry (merge (m/default-schemas)
                               {:unique-vector
                                shared.components/unique-vector-schema
                                :unique-vector-lax
                                shared.components/unique-vector-lax-schema
                                "ErrorResponse" schema/ErrorResponseSchema}
                               balance.components/registry
                               bank.components/registry
                               cash-account.components/registry
                               cash-account-migration.components/registry
                               cash-account-product.components/registry
                               companies.components/registry
                               jobs.components/registry
                               ledger-account.components/registry
                               me.components/registry
                               oauth.components/registry
                               onboarding.components/registry
                               party.components/registry
                               payee-check.components/registry
                               payment.components/registry
                               policy.components/registry
                               shared.components/registry
                               simulate.components/registry
                               tier.components/registry
                               transaction.components/registry)}}))

(defn- routes
  [ctx]
  (into
   (server/health-routes ctx)
   [["/openapi.json"
     {:get
      {:no-doc true
       :openapi
       {:info {:title "Queenswood"
               :description "Queenswood Banking API"
               :version "1.0.0"}
        :components
        {:securitySchemes
         {"bearerAuth"
          {:type :http
           :scheme :bearer
           :bearerFormat "JWT"
           :description
           "JWT issued by the Queenswood Keycloak realm. Two shapes are accepted: a service JWT minted by an organization's service-account client (`azp` is the org id, default role `org`) and a user JWT minted by the `queenswood-console` SPA via Authorization Code + PKCE (`azp` is `queenswood-console`, role `user`; once the human has completed `/v1/onboarding/me` they also carry `org`). Admin-only routes require an `admin` realm role."}}
         :parameters shared.parameters/registry
         :examples (merge
                    examples/registry
                    balance.examples/registry
                    bank.examples/registry
                    cash-account.examples/registry
                    cash-account-migration.examples/registry
                    cash-account-product.examples/registry
                    jobs.examples/registry
                    ledger-account.examples/registry
                    me.examples/registry
                    oauth.examples/registry
                    onboarding.examples/registry
                    companies.examples/registry
                    party.examples/registry
                    payee-check.examples/registry
                    payment.examples/registry
                    policy.examples/registry
                    simulate.examples/registry
                    tier.examples/registry)}}
       :handler (server/standard-openapi-handler)}}]
    (into [""
           {:interceptors (concat telemetry/trace-span
                                  (:interceptors ctx))}]
          oauth/routes)
    (into ["/v1"
           {:interceptors (concat telemetry/trace-span
                                  (:interceptors ctx)
                                  [auth/authenticate
                                   auth/authorize])
            :responses {400 (schema/ErrorResponse [#'examples/BadRequest])
                        401 (schema/ErrorResponse [#'examples/Unauthorized])
                        403 (schema/ErrorResponse [#'examples/Forbidden])
                        500 (schema/ErrorResponse
                             [#'examples/InternalServerError
                              #'examples/BadResponse])
                        503 (schema/ErrorResponse
                             [#'examples/Contention
                              #'examples/Timeout])}}]
          (concat
           balance/routes
           bank/routes
           cash-account/routes
           cash-account-migration/routes
           cash-account-product/routes
           jobs/routes
           ledger-account/routes
           me/routes
           onboarding/routes
           companies/routes
           party/routes
           payee-check/routes
           payment/routes
           policy/routes
           simulate/routes
           tier/routes))]))

(defn- add-interceptor-before-coerce
  "Splices `icept` into the router's global interceptor chain just
  before the coerce-request interceptor, so it can rewrite the
  query-params before malli sees them."
  [router-data icept]
  (update-in router-data
             [:data :interceptors]
             (fn [xs]
               (vec (mapcat (fn [i]
                              (if (= :reitit.http.coercion/coerce-request
                                     (:name i))
                                [icept i]
                                [i]))
                     xs)))))

(defn app
  [ctx]
  (let [router (http/router (routes ctx)
                            (->
                              server/standard-router-data
                              (assoc-in [:data :coercion] coercion)
                              (add-interceptor-before-coerce
                               shared.interceptors/nest-bracket-query-params)))
        bare (auth/bare-security-routes router)
        method-level (auth/method-security-routes router)]
    (when (or (seq bare) (seq method-level))
      ;; A route that demands a token without naming roles has no gate
      ;; anyone can read; one that writes its gate under a method key
      ;; has a gate `authorize` never sees. Neither may be served. A
      ;; programming error in this base's route table, caught while the
      ;; router is built — not an anomaly at a boundary, so it throws.
      ;; nosemgrep: no-raw-throw
      (throw (ex-info (str "Route table declares a security gate this "
                           "service cannot enforce."
                           (when (seq bare)
                             (str " Scheme with no roles: "
                                  (str/join ", " bare)
                                  "."))
                           (when (seq method-level)
                             (str " Security under a method key: "
                                  (str/join ", " method-level)
                                  ".")))
                      {:bare bare :method-level method-level})))
    (http/ring-handler router
                       (ring/routes (server/standard-openapi-ui-handler)
                                    (server/standard-default-handler))
                       server/standard-executor)))
