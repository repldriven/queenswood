(ns com.repldriven.queenswood.demo-digital-bank-core.platform
  (:require
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.mono.http-client.interface :as http]
    [com.repldriven.mono.json.interface :as json]
    [com.repldriven.mono.utility.interface :as util]))

(def ^:private request-timeout-ms 10000)

(def ^:private token-margin-ms
  "How long before its expiry a cached token is treated as expired, so a
  call in flight when it lapses is not refused."
  30000)

(defn client
  "A client over the platform at `url` as the organisation `client-id`
  names, holding its token cache."
  [config]
  {:config config :token (atom nil)})

(defn- scope
  [status]
  (if (= "live" (name status)) "queenswood-api-live" "queenswood-api-test"))

(defn- mint-token
  [config]
  (let [{:keys [url client-id client-secret status]} config
        started (util/now)]
    (let-nom> [res (http/request
                    {:method :post
                     :url (str url "/oauth/token")
                     :headers {"content-type"
                               "application/x-www-form-urlencoded"}
                     :body (str "grant_type=client_credentials"
                                "&client_id=" (http/url-encode client-id)
                                "&client_secret=" (http/url-encode
                                                   client-secret)
                                "&scope=" (scope status))
                     :timeout request-timeout-ms})
               body (http/res->edn res)]
      (if (and (= 200 (:status res)) (:access_token body))
        {:token (:access_token body)
         :expires-at (+ started (* 1000 (:expires_in body 60)))}
        (error/fail :platform/token
                    {:message "the platform refused the client credentials"
                     :status (:status res)
                     :body body})))))

(defn- fresh?
  [cached]
  (and cached (< (+ (util/now) token-margin-ms) (:expires-at cached))))

(defn bearer
  "The organisation's bearer token, minted on first use and again once
  the cached one is within a margin of expiring."
  [client]
  (let [{:keys [config token]} client
        cached @token]
    (if (fresh? cached)
      (:token cached)
      (let-nom> [minted (mint-token config)]
        (reset! token minted)
        (:token minted)))))

(defn- refusal
  [res body]
  (error/reject :platform/refused
                {:message (or (:detail body) (:title body) "refused")
                 :status (:status res)
                 :type (:type body)}))

(defn- call
  "One request to the platform, bearing the token. A 2xx answers with
  its body; a 4xx is a `:platform/refused` rejection carrying the
  problem details; anything else is a `:platform/request` error."
  [client {:keys [method path body idempotency-key query]}]
  (let [{:keys [url]} (:config client)]
    (let-nom> [token (bearer client)
               res (http/request
                    (cond-> {:method method
                             :url (str url path)
                             :headers (cond-> {"authorization"
                                               (str "Bearer " token)
                                               "accept" "application/json"}
                                              body
                                              (assoc "content-type"
                                                     "application/json")

                                              idempotency-key
                                              (assoc "idempotency-key"
                                                     idempotency-key))
                             :timeout request-timeout-ms}

                            body
                            (assoc :body (json/write-str body))

                            query
                            (assoc :query-params query)))
               parsed (http/res->edn res)
               status (:status res)]
      (cond (<= 200 status 299)
            parsed
            (<= 400 status 499)
            (refusal res parsed)
            :else
            (error/fail :platform/request
                        {:message (str "the platform answered " status)
                         :status status
                         :body parsed})))))

(defn register-party
  "Register a person, under `idempotency-key`, and answer the party."
  [client idempotency-key party]
  (call client
        {:method :post
         :path "/v1/parties"
         :body party
         :idempotency-key idempotency-key}))

(defn get-party
  [client party-id]
  (call client {:method :get :path (str "/v1/parties/" party-id)}))

(defn list-products
  [client]
  (call client {:method :get :path "/v1/cash-account-products"}))

(defn get-account
  "The account with its balances embedded."
  [client account-id]
  (call client
        {:method :get
         :path (str "/v1/cash-accounts/" account-id)
         :query {"embed[balances]" "true"}}))

(defn list-transactions
  [client account-id]
  (call client
        {:method :get
         :path (str "/v1/cash-accounts/" account-id "/transactions")}))

(defn open-account
  "Open an account for a party against a product, under
  `idempotency-key`. The platform answers it `opening`; it is `opened`
  a moment later, on a read."
  [client idempotency-key account]
  (call client
        {:method :post
         :path "/v1/cash-accounts"
         :body account
         :idempotency-key idempotency-key}))

(defn check-payee
  "Check a payee's name against the one their bank holds, under
  `idempotency-key`."
  [client idempotency-key check]
  (call client
        {:method :post
         :path "/v1/payee-checks"
         :body check
         :idempotency-key idempotency-key}))

(defn submit-outbound-payment
  "Submit a Faster Payment out of one of the bank's accounts, under
  `idempotency-key`. The platform answers intent accepted, the payment
  `pending`, with the amount set aside."
  [client idempotency-key payment]
  (call client
        {:method :post
         :path "/v1/payments/outbound"
         :body payment
         :idempotency-key idempotency-key}))

(defn submit-internal-payment
  "Move money between two of the bank's accounts, under
  `idempotency-key`. Settled as it is answered."
  [client idempotency-key payment]
  (call client
        {:method :post
         :path "/v1/payments/internal"
         :body payment
         :idempotency-key idempotency-key}))
