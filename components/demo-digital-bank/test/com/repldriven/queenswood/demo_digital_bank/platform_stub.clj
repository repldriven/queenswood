(ns com.repldriven.queenswood.demo-digital-bank.platform-stub
  "A stand-in for the platform, served in-process, answering in the
  shapes `test-api-scenarios` pins for the routes the bank calls: the
  token endpoint, registering and reading a party, listing products,
  opening and reading an account with its balances and its
  transactions, checking a payee, and submitting outbound and internal
  payments. A party is pending when registered and decided when read,
  rejected where the given name contains `reject`, the way the Onfido
  simulator decides; an account is `opening` when opened and `opened`
  when read, the way the watcher flips it; a payee check decides from
  the name, on the ClearBank simulator's `COP_NOMATCH`,
  `COP_CLOSEMATCH` and `COP_UNAVAILABLE`. An outbound payment sets its
  amount aside and stays `pending` until a test settles or fails it
  with `settle-outbound` or `fail-outbound`. Accounts and legs may
  also be seeded by a test. Registers the
  `demo-digital-bank/platform-stub` and `platform-stub-state`
  component kinds."
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
                        :status "published"
                        :name "1 Year Fixed"
                        :product-type "term-deposit"
                        :interest-rate-bps 465}]}]})

(def sort-code "040075")

(defn initial-state
  []
  {:tokens 0
   :parties {}
   :by-key {}
   :accounts {}
   :transactions {}
   :payments {}
   :numbers 31908240})

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

(defn- replay
  "The answer already given under the request's idempotency key, or
  nil."
  [state request]
  (get-in @state [:by-key (get-in request [:headers "idempotency-key"])]))

(defn- remember
  [state request response]
  (swap! state
    assoc-in
    [:by-key (get-in request [:headers "idempotency-key"])]
    response)
  response)

(defn- register-party
  [state]
  (fn [request]
    (or (replay state request)
        (let [{:keys [body-params]} request
              now (util/now-rfc3339)
              party-id (util/generate-id "pty")
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
          (swap! state assoc-in [:parties party-id] party)
          (remember state request {:status 200 :body party})))))

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

(defn- published
  [product-id]
  (some (fn [p]
          (when (= product-id (:product-id p))
            (some (fn [v] (when (= "published" (:status v)) v))
                  (:versions p))))
        (:items products)))

(defn- new-account
  [state body]
  (let [{:keys [party-id name currency product-id]} body
        version (published product-id)
        number (:numbers (swap! state update :numbers inc))
        now (util/now-rfc3339)]
    {:bank-id "bnk.00000000000000000000000001"
     :account-id (util/generate-id "acc")
     :party-id party-id
     :name name
     :currency currency
     :product-id product-id
     :version-id (:version-id version)
     :product-type (:product-type version)
     :account-type "personal"
     :account-status "opening"
     :payment-addresses [{:scheme "scan"
                          :scan {:sort-code sort-code
                                 :account-number (str number)}}]
     :posted-balance {:value 0 :currency currency}
     :available-balance {:value 0 :currency currency}
     :created-at now
     :updated-at now}))

(defn- open-account
  [state]
  (fn [request]
    (or (replay state request)
        (let [{:keys [body-params]} request
              party (get-in @state [:parties (:party-id body-params)])]
          (cond (nil? party)
                (problem 404 "REJECTED" ":party/not-found" "no such party")

                (not= "active" (:status (decide party)))
                (problem 422
                         "REJECTED"
                         ":cash-account/party-inactive"
                         "the party is not active")

                (nil? (published (:product-id body-params)))
                (problem 422
                         "REJECTED"
                         ":cash-account-product/not-published"
                         "no published version")

                :else
                (let [account (new-account state body-params)]
                  (swap! state
                    (fn [s]
                      (-> s
                          (assoc-in [:accounts (:account-id account)] account)
                          (assoc-in [:transactions (:account-id account)]
                                    []))))
                  (remember state
                            request
                            {:status 200
                             :body (dissoc account
                                    :posted-balance
                                    :available-balance)})))))))

(defn- opened
  "An account read is an opened account: the watcher has been."
  [state account-id]
  (when (get-in @state [:accounts account-id])
    (get-in (swap! state
              assoc-in
              [:accounts account-id :account-status]
              "opened")
            [:accounts account-id])))

(defn- get-account
  [state]
  (fn [request]
    (let [account-id (get-in request [:path-params :account-id])
          embed? (= "true" (get-in request [:query-params "embed[balances]"]))]
      (if-let [account (opened state account-id)]
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
         :body {:items (get-in @state [:transactions account-id] [])}}
        (problem 404 "REJECTED" ":cash-account/not-found" "no such account")))))

(defn- check-payee
  [state]
  (fn [request]
    (or (replay state request)
        (let [{:keys [body-params]} request
              name (:creditor-name body-params "")
              result (cond (str/includes? name "COP_NOMATCH")
                           {:match-result "no-match"
                            :reason-code "ANNM"
                            :reason "Account name does not match"}

                           (str/includes? name "COP_CLOSEMATCH")
                           {:match-result "close-match"
                            :actual-name (str/trim (str/replace
                                                    name
                                                    "COP_CLOSEMATCH"
                                                    ""))
                            :reason-code "MBAM"
                            :reason "Close match"}

                           (str/includes? name "COP_UNAVAILABLE")
                           {:match-result "unavailable"
                            :reason-code "ACNS"
                            :reason "Account not supported"}

                           :else
                           {:match-result "match"})
              now (util/now-rfc3339)]
          (remember state
                    request
                    {:status 201
                     :body {:check-id (util/generate-id "chk")
                            :request body-params
                            :result result
                            :created-at now
                            :expires-at now}})))))

(defn- leg
  [account-id transaction-id type side status amount currency reference]
  {:leg-id (util/generate-id "leg")
   :transaction-id transaction-id
   :transaction-type type
   :status "posted"
   :account-id account-id
   :balance-type "default"
   :balance-status status
   :side side
   :amount amount
   :currency currency
   :reference reference
   :created-at (util/now-rfc3339)})

(defn- adjust
  "Move an account's balances by `posted` and `available`, and append
  legs to its transactions."
  [state account-id posted available legs]
  (swap! state
    (fn [s]
      (-> s
          (update-in [:accounts account-id :posted-balance :value] + posted)
          (update-in [:accounts account-id :available-balance :value]
                     +
                     available)
          (update-in [:transactions account-id] into legs)))))

(defn- operable
  [state account-id]
  (let [account (get-in @state [:accounts account-id])]
    (cond (nil? account)
          (problem 404 "REJECTED" ":cash-account/not-found" "no such account")

          (not= "opened" (:account-status account))
          (problem 409
                   "REJECTED"
                   ":cash-account/invalid-status"
                   (str "the account is " (:account-status account)))

          :else
          account)))

(defn- covered
  [account amount]
  (when (< (get-in account [:available-balance :value]) amount)
    (problem 422
             "REJECTED"
             ":balance/insufficient-funds"
             "not enough available")))

(defn- submit-internal-payment
  [state]
  (fn [request]
    (or
     (replay state request)
     (let [{:keys [body-params]} request
           {:keys [debtor-account-id creditor-account-id amount currency
                   reference]}
           body-params
           debtor (operable state debtor-account-id)
           creditor (operable state creditor-account-id)]
       (cond (:status debtor)
             debtor

             (:status creditor)
             creditor

             (covered debtor amount)
             (covered debtor amount)

             :else
             (let [transaction-id (util/generate-id "txn")
                   now (util/now-rfc3339)]
               (adjust state
                       debtor-account-id
                       (- amount)
                       (- amount)
                       [(leg debtor-account-id
                             transaction-id
                             "internal-transfer"
                             "debit"
                             "posted"
                             amount
                             currency
                             reference)])
               (adjust state
                       creditor-account-id
                       amount
                       amount
                       [(leg creditor-account-id
                             transaction-id
                             "internal-transfer"
                             "credit"
                             "posted"
                             amount
                             currency
                             reference)])
               (remember state
                         request
                         {:status 200
                          :body {:payment-id (util/generate-id "pmt")
                                 :bank-id "bnk.00000000000000000000000001"
                                 :debtor-account-id debtor-account-id
                                 :creditor-account-id creditor-account-id
                                 :currency currency
                                 :amount amount
                                 :transaction-id transaction-id
                                 :reference reference
                                 :business-day (subs now 0 10)
                                 :created-at now
                                 :updated-at now}})))))))

(defn- submit-outbound-payment
  [state]
  (fn [request]
    (or (replay state request)
        (let [{:keys [body-params]} request
              {:keys [debtor-account-id creditor-bban creditor-name amount
                      currency reference scheme]}
              body-params
              debtor (operable state debtor-account-id)]
          (cond (:status debtor)
                debtor

                (covered debtor amount)
                (covered debtor amount)

                :else
                (let [transaction-id (util/generate-id "txn")
                      payment-id (util/generate-id "pmt")
                      now (util/now-rfc3339)
                      payment {:payment-id payment-id
                               :bank-id "bnk.00000000000000000000000001"
                               :scheme scheme
                               :debtor-account-id debtor-account-id
                               :creditor-bban creditor-bban
                               :creditor-name creditor-name
                               :currency currency
                               :amount amount
                               :payment-status "pending"
                               :transaction-id transaction-id
                               :reference reference
                               :business-day (subs now 0 10)
                               :created-at now
                               :updated-at now}]
                  (adjust state
                          debtor-account-id
                          0
                          (- amount)
                          [(leg debtor-account-id
                                transaction-id
                                "outbound-transfer"
                                "debit"
                                "pending-outgoing"
                                amount
                                currency
                                reference)])
                  (swap! state assoc-in [:payments payment-id] payment)
                  (remember state request {:status 200 :body payment})))))))

(defn- get-outbound-payment
  [state]
  (fn [request]
    (let [payment-id (get-in request [:path-params :payment-id])]
      (if-let [payment (get-in @state [:payments payment-id])]
        {:status 200 :body payment}
        (problem 404 "REJECTED" ":payment/not-found" "no such payment")))))

(defn- routes
  [state]
  [["/oauth/token" {:post {:handler (token state)}}]
   ["/v1" {:interceptors [authenticate]}
    ["/parties"
     ["" {:post {:handler (register-party state)}}]
     ["/{party-id}" {:get {:handler (get-party state)}}]]
    ["/cash-account-products" {:get {:handler (list-products state)}}]
    ["/cash-accounts"
     ["" {:post {:handler (open-account state)}}]
     ["/{account-id}"
      ["" {:get {:handler (get-account state)}}]
      ["/transactions" {:get {:handler (list-transactions state)}}]]]
    ["/payee-checks" {:post {:handler (check-payee state)}}]
    ["/payments"
     ["/internal" {:post {:handler (submit-internal-payment state)}}]
     ["/outbound"
      ["" {:post {:handler (submit-outbound-payment state)}}]
      ["/{payment-id}" {:get {:handler (get-outbound-payment state)}}]]]]])

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

(system/defcomponents :demo-digital-bank
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

(defn settle-outbound
  "The scheme settles a pending payment: the reservation is released
  and the money leaves the posted balance, as the platform's
  settlement posts it."
  [state payment-id]
  (let [{:keys [debtor-account-id amount currency reference]}
        (get-in @state [:payments payment-id])
        transaction-id (util/generate-id "txn")]
    (adjust state
            debtor-account-id
            (- amount)
            0
            [(leg debtor-account-id
                  transaction-id
                  "outbound-transfer"
                  "credit"
                  "pending-outgoing"
                  amount
                  currency
                  reference)
             (leg debtor-account-id
                  transaction-id
                  "outbound-transfer"
                  "debit"
                  "posted"
                  amount
                  currency
                  reference)])
    (get-in (swap! state
              assoc-in
              [:payments payment-id :payment-status]
              "completed")
            [:payments payment-id])))

(defn fail-outbound
  "The scheme refuses a pending payment: the reservation is released
  and the money is available again."
  [state payment-id]
  (let [{:keys [debtor-account-id amount currency reference]}
        (get-in @state [:payments payment-id])]
    (adjust state
            debtor-account-id
            0
            amount
            [(leg debtor-account-id
                  (util/generate-id "txn")
                  "outbound-transfer"
                  "credit"
                  "pending-outgoing"
                  amount
                  currency
                  reference)])
    (get-in (swap! state
              update
              :payments
              (fn [payments]
                (-> payments
                    (assoc-in [payment-id :payment-status] "failed")
                    (assoc-in [payment-id :cancellation-code]
                              "CB_AssessmentFailed"))))
            [:payments payment-id])))
