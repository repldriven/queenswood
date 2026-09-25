(ns com.repldriven.queenswood.api.api
  (:require
    [com.repldriven.queenswood.api.auth :as auth]
    [com.repldriven.queenswood.api.errors :as errors]

    [com.repldriven.queenswood.api.access.routes :as access]
    [com.repldriven.queenswood.api.balance.routes :as balance]
    [com.repldriven.queenswood.api.bank.routes :as bank]
    [com.repldriven.queenswood.api.cash-account.routes :as cash-account]
    [com.repldriven.queenswood.api.cash-account-migration.routes :as
     cash-account-migration]
    [com.repldriven.queenswood.api.cash-account-product.routes :as
     cash-account-product]
    [com.repldriven.queenswood.api.companies.routes :as companies]
    [com.repldriven.queenswood.api.jobs.routes :as jobs]
    [com.repldriven.queenswood.api.ledger-account.routes :as ledger-account]
    [com.repldriven.queenswood.api.me.routes :as me]
    [com.repldriven.queenswood.api.oauth.routes :as oauth]
    [com.repldriven.queenswood.api.party.routes :as party]
    [com.repldriven.queenswood.api.payee-check.routes :as payee-check]
    [com.repldriven.queenswood.api.payment.routes :as payment]
    [com.repldriven.queenswood.api.policy.routes :as policy]
    [com.repldriven.queenswood.api.reward.routes :as reward]
    [com.repldriven.queenswood.api.shared.interceptors :as shared.interceptors]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]
    [com.repldriven.queenswood.api.simulate.routes :as simulate]
    [com.repldriven.queenswood.api.tier.routes :as tier]
    [com.repldriven.queenswood.api.webhook.document :as webhook.document]
    [com.repldriven.queenswood.api.webhook.routes :as webhook]

    [com.repldriven.queenswood.access-api.interface :as access-api]
    [com.repldriven.queenswood.api-schema.interface :as api-schema]
    [com.repldriven.queenswood.balance-api.interface :as balance-api]
    [com.repldriven.queenswood.bank-api.interface :as bank-api]
    [com.repldriven.queenswood.cash-account-api.interface :as cash-account-api]
    [com.repldriven.queenswood.cash-account-migration-api.interface :as
     cash-account-migration-api]
    [com.repldriven.queenswood.cash-account-product-api.interface :as
     cash-account-product-api]
    [com.repldriven.queenswood.company-api.interface :as company-api]
    [com.repldriven.queenswood.job-api.interface :as job-api]
    [com.repldriven.queenswood.ledger-account-api.interface :as
     ledger-account-api]
    [com.repldriven.queenswood.me-api.interface :as me-api]
    [com.repldriven.queenswood.oauth-api.interface :as oauth-api]
    [com.repldriven.queenswood.party-api.interface :as party-api]
    [com.repldriven.queenswood.payee-check-api.interface :as payee-check-api]
    [com.repldriven.queenswood.payment-api.interface :as payment-api]
    [com.repldriven.queenswood.policy-api.interface :as policy-api]
    [com.repldriven.queenswood.reward-api.interface :as reward-api]
    [com.repldriven.queenswood.simulate-api.interface :as simulate-api]
    [com.repldriven.queenswood.tier-api.interface :as tier-api]
    [com.repldriven.queenswood.transaction-api.interface :as transaction-api]
    [com.repldriven.queenswood.webhook.interface :as webhook-api]

    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.telemetry.interface :as telemetry]

    [malli.core :as m]
    [malli.json-schema :as mjs]
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

(def ^:private schema-registry
  "Every named schema the document may reference, each domain's
  registry merged into malli's own. The coercion resolves
  `[:ref \"X\"]` through it, and `notification-schemas` projects the
  notification out of it."
  (merge (m/default-schemas)
         {:unique-vector api-schema/unique-vector-schema
          :unique-vector-lax api-schema/unique-vector-lax-schema
          "ErrorResponse" api-schema/ErrorResponseSchema}
         access-api/registry
         balance-api/registry
         bank-api/registry
         cash-account-api/registry
         cash-account-migration-api/registry
         cash-account-product-api/registry
         company-api/registry
         job-api/registry
         ledger-account-api/registry
         me-api/registry
         oauth-api/registry
         party-api/registry
         payee-check-api/registry
         payment-api/registry
         reward-api/registry
         policy-api/registry
         api-schema/registry
         simulate-api/registry
         tier-api/registry
         transaction-api/registry
         webhook-api/registry))

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
    :options {:registry schema-registry}}))

(def ^:private notification-schemas
  "The notification's JSON Schema and every schema it reaches, keyed as
  `components/schemas` keys them. Reitit fills that key from the route
  schemas it transforms and then replaces it wholesale, so a schema
  only the `webhooks` object references reaches the document after its
  handler has run."
  (delay (:definitions (mjs/transform [:ref "WebhookNotification"]
                                      {:registry schema-registry
                                       ::mjs/definitions-path
                                       "#/components/schemas/"}))))

(defn- openapi-handler
  "The standard handler, with the notification's schemas merged under
  the ones reitit collected, so the `webhooks` object's `$ref`
  resolves, and the paths sorted, so each tag lists a collection, then
  its items, then their actions."
  []
  (let [handler (server/standard-openapi-handler)
        complete (fn [response]
                   (-> response
                       (update-in [:body :components :schemas]
                                  #(merge @notification-schemas %))
                       (update-in [:body :paths]
                                  #(into (sorted-map) %))))]
    (fn
      ([request] (complete (handler request)))
      ([request respond raise]
       (handler request (comp respond complete) raise)))))

(defn- routes
  [ctx]
  (into
   (server/health-routes ctx)
   [["/openapi.json"
     {:get
      {:no-doc true
       :openapi
       {:info {:title "Queenswood"
               :description
               (str "**Open-source core banking.** Whether you're building a "
                    "bank or embedding banking into your product, Queenswood "
                    "runs the banking behind it. It provides customer "
                    "onboarding with identity checks, products and accounts, "
                    "payments, interest and rewards, a general ledger, "
                    "policies, end-of-day processing, webhooks and an "
                    "operator console, all of it configured and driven "
                    "through this API. You bring the banking licence, the "
                    "clearing partner and the identity provider.")
               :version "0.0.5"}
        :tags
        [{:name "OAuth"
          :description
          "Issuing tokens, and the documents a client reads to verify them."}
         {:name "Me"
          :description
          "The signed-in person: who they are, the banks they belong to, and the invitations waiting for them."}
         {:name "Memberships"
          :description
          "The bank's members, and the role each holds."}
         {:name "Invitations"
          :description
          "Invitations to join the bank, and resending or withdrawing them."}
         {:name "Audit"
          :description
          "A bank's audit log: every change to who may act for it, with who made it and why."}
         {:name "Companies"
          :description
          "Looking a company up in the company registry before creating a bank for it."}
         {:name "Banks"
          :description
          "Creating banks, and changing a bank's status and tier."}
         {:name "Tiers"
          :description
          "The tiers an operator can place a bank on."}
         {:name "Policies"
          :description
          "The policies that grant a bank its capabilities and limits."}
         {:name "Parties"
          :description
          "The customers a bank opens accounts for."}
         {:name "Cash Account Products"
          :description
          "The products cash accounts are opened on, versioned as drafts that are published."}
         {:name "Cash Accounts"
          :description
          "Opening, suspending, resuming and closing cash accounts, and reading their transactions."}
         {:name "Balances"
          :description
          "A cash account's balances."}
         {:name "Ledger Accounts"
          :description
          "The bank's ledger accounts, their balances, and its trial balance."}
         {:name "Payments"
          :description
          "Submitting internal and outbound payments, and reading inbound ones."}
         {:name "Payee Checks"
          :description
          "Checking a payee's name against their account before paying them."}
         {:name "Rewards"
          :description
          "The rewards paid or owed to an account."}
         {:name "Cash Account Migrations"
          :description
          "Moving cash accounts from one product to another, previewed before they are approved."}
         {:name "Jobs"
          :description
          "Scheduled jobs, their schedules, and their runs."}
         {:name "Webhook Endpoints"
          :description
          "The endpoints a bank is notified at when its records change, and the deliveries made to them."}
         {:name "Simulate"
          :description
          "A stand-in for an inbound transfer, crediting a test bank's own funds as money arriving from outside."}]
        :components
        {:securitySchemes
         {"bearerAuth"
          {:type :http
           :scheme :bearer
           :bearerFormat "JWT"
           :description
           "JWT issued by the Queenswood Keycloak realm. Two shapes are accepted: a service JWT minted by an organization's service-account client (`azp` is the org id) and a user JWT minted by the `queenswood-console` SPA via Authorization Code + PKCE (`azp` is `queenswood-console`). Each operation's gate names one or more of six roles: `user`, any signed-in person; `admin`, a Queenswood operator; and the organisation levels `org:viewer`, `org:developer`, `org:admin` and `org:owner`, where a member holds the level of their role in the bank the `Bank-Id` header names and every level below it. A service JWT carries `org:viewer` and `org:developer` for its own bank."}}
         :parameters shared.parameters/registry
         :examples (merge
                    api-schema/examples
                    access-api/examples
                    balance-api/examples
                    bank-api/examples
                    cash-account-api/examples
                    cash-account-migration-api/examples
                    cash-account-product-api/examples
                    job-api/examples
                    ledger-account-api/examples
                    oauth-api/examples
                    company-api/examples
                    party-api/examples
                    payee-check-api/examples
                    payment-api/examples
                    reward-api/examples
                    policy-api/examples
                    simulate-api/examples
                    tier-api/examples
                    webhook-api/examples)}
        :webhooks webhook.document/webhooks}
       :handler (openapi-handler)}}]
    (into [""
           {:interceptors (concat telemetry/trace-span
                                  (:interceptors ctx))}]
          oauth/routes)
    (into ["/v1"
           {:interceptors (concat telemetry/trace-span
                                  (:interceptors ctx)
                                  [server/credential
                                   server/authenticate-with-provider
                                   server/claims->scopes
                                   auth/claims->principal
                                   auth/require-bank
                                   server/require-scopes])
            :scopes auth/scopes
            :exclusive-scopes auth/exclusive-scopes
            :unauthorized (errors/unauthenticated-response)
            :forbidden (errors/forbidden-response)
            :responses {400 (api-schema/ErrorResponse [#'api-schema/BadRequest])
                        401 (api-schema/ErrorResponse
                             [#'api-schema/Unauthorized])
                        403 (api-schema/ErrorResponse [#'api-schema/Forbidden])
                        500 (api-schema/ErrorResponse
                             [#'api-schema/InternalServerError
                              #'api-schema/BadResponse])
                        503 (api-schema/ErrorResponse
                             [#'api-schema/Contention
                              #'api-schema/Timeout])}}]
          (concat
           access/routes
           balance/routes
           bank/routes
           cash-account/routes
           cash-account-migration/routes
           cash-account-product/routes
           jobs/routes
           ledger-account/routes
           me/routes
           companies/routes
           party/routes
           payee-check/routes
           payment/routes
           policy/routes
           reward/routes
           simulate/routes
           tier/routes
           webhook/routes))]))

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

(defn router
  "The compiled route tree, without the ring handler around it. Split
  out so a test can walk `reitit.core/routes` and assert over route
  data — `idempotency-coverage-test` does — without booting a system."
  [ctx]
  (http/router (routes ctx)
               (-> server/standard-router-data
                   (assoc-in [:data :coercion] coercion)
                   (add-interceptor-before-coerce
                    shared.interceptors/nest-bracket-query-params))))

(defn app
  [ctx]
  (let [compiled (router ctx)]
    (http/ring-handler compiled
                       (ring/routes (server/standard-openapi-ui-handler)
                                    (server/standard-default-handler))
                       server/standard-executor)))
