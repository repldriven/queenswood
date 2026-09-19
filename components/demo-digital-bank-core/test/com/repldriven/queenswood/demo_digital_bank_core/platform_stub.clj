(ns com.repldriven.queenswood.demo-digital-bank-core.platform-stub
  "A stand-in for the platform, served in-process, answering in the
  shapes `test-api-scenarios` pins for the routes the bank calls: the
  token endpoint, registering and reading a party, listing products,
  and reading an account with its balances and its transactions. A
  party is pending when registered and decided when read, rejected
  where the given name contains `reject`, the way the Onfido simulator
  decides. Accounts and legs are seeded into its state by a test.
  Registers the `demo-digital-bank-core/platform-stub` and
  `platform-stub-state` component kinds."
  (:require
    [com.repldriven.mono.server.interface :as server]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.utility.interface :as util]

    [reitit.http :as http]
    [reitit.ring :as ring]

    [clojure.string :as str]))

(def products
  "The three products the seed publishes, one version each."
  {:items [{:product-id "prd.00000000000000000000000001"
            :versions [{:product-id "prd.00000000000000000000000001"
                        :version-id "prv.00000000000000000000000001"
                        :status "published"
                        :name "Everyday"
                        :product-type "current"
                        :interest-rate-bps 0}]}
           {:product-id "prd.00000000000000000000000002"
            :versions [{:product-id "prd.00000000000000000000000002"
                        :version-id "prv.00000000000000000000000002"
                        :status "published"
                        :name "Rainy Day"
                        :product-type "savings"
                        :interest-rate-bps 410}]}
           {:product-id "prd.00000000000000000000000003"
            :versions [{:product-id "prd.00000000000000000000000003"
                        :version-id "prv.00000000000000000000000003"
                        :status "draft"
                        :name "1 Year Fixed"
                        :product-type "term-deposit"
                        :interest-rate-bps 465}]}]})

(defn initial-state
  []
  {:tokens 0 :parties {} :by-key {} :accounts {} :transactions {}})

(defn- problem
  [status title type detail]
  {:status status
   :body {:title title :type type :status status :detail detail}})

(defn- bearer?
  [request]
  (str/starts-with? (get-in request [:headers "authorization"] "")
                    "Bearer tok-"))

(def ^:private authenticate
  {:name ::authenticate
   :enter (fn [ctx]
            (if (bearer? (:request ctx))
              ctx
              (assoc ctx
                     :response
                     (problem 401 "UNAUTHORIZED" "auth/missing" "no token"))))})

(defn- token
  [state]
  (fn [request]
    (let [{:keys [form-params]} request
          n (:tokens (swap! state update :tokens inc))]
      (if (= "client_credentials" (get form-params "grant_type"))
        {:status 200
         :body {:access_token (str "tok-" n)
                :token_type "Bearer"
                :expires_in 300
                :scope (get form-params "scope")}}
        {:status 400 :body {:error "unsupported_grant_type"}}))))

(defn- register-party
  [state]
  (fn [request]
    (let [{:keys [body-params headers]} request
          key (get headers "idempotency-key")
          now (util/now-rfc3339)]
      (if-let [party-id (get-in @state [:by-key key])]
        {:status 200 :body (get-in @state [:parties party-id])}
        (let [party-id (util/generate-id "pty")
              party (assoc (select-keys body-params
                                        [:type :display-name :given-name
                                         :family-name :date-of-birth
                                         :nationality :address
                                         :national-identifier])
                           :bank-id "bnk.00000000000000000000000001"
                           :party-id party-id
                           :status "pending"
                           :created-at now
                           :updated-at now)]
          (swap! state
            (fn [s]
              (-> s
                  (assoc-in [:parties party-id] party)
                  (assoc-in [:by-key key] party-id))))
          {:status 200 :body party})))))

(defn- decide
  [party]
  (assoc party
         :status
         (if (str/includes? (str/lower-case (:given-name party "")) "reject")
           "rejected"
           "active")))

(defn- get-party
  [state]
  (fn [request]
    (let [party-id (get-in request [:path-params :party-id])]
      (if-let [party (get-in @state [:parties party-id])]
        {:status 200 :body (decide party)}
        (problem 404 "REJECTED" ":party/not-found" "no such party")))))

(defn- list-products [_] (fn [_] {:status 200 :body products}))

(defn- get-account
  [state]
  (fn [request]
    (let [account-id (get-in request [:path-params :account-id])
          embed? (= "true" (get-in request [:query-params "embed[balances]"]))]
      (if-let [account (get-in @state [:accounts account-id])]
        {:status 200
         :body (if embed?
                 account
                 (dissoc account :balances :posted-balance :available-balance))}
        (problem 404 "REJECTED" ":cash-account/not-found" "no such account")))))

(defn- list-transactions
  [state]
  (fn [request]
    (let [account-id (get-in request [:path-params :account-id])]
      (if (get-in @state [:accounts account-id])
        {:status 200
         :body {:transactions (get-in @state [:transactions account-id] [])}}
        (problem 404 "REJECTED" ":cash-account/not-found" "no such account")))))

(defn- routes
  [state]
  [["/oauth/token" {:post {:handler (token state)}}]
   ["/v1" {:interceptors [authenticate]}
    ["/parties"
     ["" {:post {:handler (register-party state)}}]
     ["/{party-id}" {:get {:handler (get-party state)}}]]
    ["/cash-account-products" {:get {:handler (list-products state)}}]
    ["/cash-accounts/{account-id}"
     ["" {:get {:handler (get-account state)}}]
     ["/transactions" {:get {:handler (list-transactions state)}}]]]])

(defn handler
  "A ring handler over `state`, an atom holding `initial-state`."
  [state]
  (http/ring-handler (http/router (routes state) server/standard-router-data)
                     (ring/routes (server/standard-default-handler))
                     server/standard-executor))

(def ^:private state-component
  {:system/start (fn [{:system/keys [instance]}]
                   (or instance (atom (initial-state))))
   :system/instance-schema some?})

(def ^:private handler-component
  {:system/start
   (fn [{:system/keys [config instance]}]
     (or instance (let [{:keys [state]} config] (fn [_ctx] (handler state)))))
   :system/config {:state system/required-component}
   :system/instance-schema fn?})

(system/defcomponents :demo-digital-bank-core
                      {:platform-stub-state state-component
                       :platform-stub handler-component})

(defn seed-account
  "Put an account and its legs into the stand-in's state, as the
  platform would answer them."
  [state account legs]
  (swap! state
    (fn [s]
      (-> s
          (assoc-in [:accounts (:account-id account)] account)
          (assoc-in [:transactions (:account-id account)] legs)))))
