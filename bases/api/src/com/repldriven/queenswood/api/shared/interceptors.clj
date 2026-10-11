(ns com.repldriven.queenswood.api.shared.interceptors
  "Cross-cutting reitit interceptors for the bank-api router."
  (:require
    [com.repldriven.queenswood.api.errors :as errors]
    [com.repldriven.queenswood.api.shared.actor :as actor]

    [com.repldriven.queenswood.access-log.interface :as access-log]
    [com.repldriven.queenswood.bank-query.interface :as banks]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.telemetry.interface :as telemetry]
    [com.repldriven.mono.utility.interface :as utility]

    [clojure.string :as str]
    [sieppari.context :as sc]))

(defn- nest-bracket-entry
  "If `k` looks like `outer[inner]`, returns `[:outer-kw :inner-kw v]`.
  Otherwise returns `[k v]` unchanged. Keys with more than one
  bracket pair (`a[b][c]`) aren't supported — we treat them as
  opaque and leave them to fail the top-level closed-map check."
  [k v]
  (if-let [[_ outer inner]
           (re-matches #"^([^\[\]]+)\[([^\[\]]+)\]$" (str/trim (name k)))]
    [(keyword outer) (keyword inner) v]
    [(if (keyword? k) k (keyword k)) v]))

(defn- nest-params
  "Walks a flat query-params map like `{\"embed[transactions]\" \"false\"
  \"page[size]\" \"20\"}` into a nested map
  `{:embed {:transactions \"false\"} :page {:size \"20\"}}`. Flat,
  non-bracketed keys are kept at the top level.

  Blank flat values (e.g. `?page=` from schemathesis probing the bare
  deepObject serialization) are dropped — a deepObject parameter with
  no `[inner]` segment has no meaningful value, and treating it as
  \"not provided\" keeps optional params schema-compliant rather than
  forcing a 400 on a string/object type mismatch."
  [params]
  (reduce-kv
   (fn [acc k v]
     (let [entry (nest-bracket-entry k v)]
       (if (= 3 (count entry))
         (let [[outer inner v'] entry]
           (update acc outer (fnil assoc {}) inner v'))
         (let [[k' v'] entry]
           (if (and (string? v') (str/blank? v'))
             acc
             (assoc acc k' v'))))))
   {}
   params))

(def nest-bracket-query-params
  "Reitit interceptor that rewrites flat bracketed query-params (the
  `embed[transactions]` form emitted by clients) into nested maps
  (`{:embed {:transactions …}}`) so malli can model them as canonical
  OpenAPI `deepObject`-styled parameters. Sits between the stock
  `parameters-interceptor` (which parses the raw query string) and
  `coerce-request-interceptor` (which validates the result)."
  {:name ::nest-bracket-query-params
   :enter (fn [ctx]
            (update-in ctx
                       [:request :query-params]
                       (fn [qp] (when qp (nest-params qp)))))})

(def request-id
  "Gives every request an id, a uuidv7, as `:request-id`, which a write
  passes to the domain beside the actor so the audit entries it records
  name the request, and answers it in the `Request-Id` header."
  {:name ::request-id
   :enter (fn [ctx]
            (assoc-in ctx [:request :request-id] (str (utility/uuidv7))))
   :leave (fn [ctx]
            (cond-> ctx
                    (:response ctx)
                    (assoc-in [:response :headers "Request-Id"]
                     (get-in ctx [:request :request-id]))))})

(defn- client-address
  "The address the nearest proxy saw the call come from, the last in
  `X-Forwarded-For`, or the connection's own where no proxy sent one."
  [request]
  (if-let [forwarded (get-in request [:headers "x-forwarded-for"])]
    (str/trim (last (str/split forwarded #",")))
    (:remote-addr request)))

(defn- trace-id
  "The id of the trace the call runs in, or nil outside one."
  []
  (let [id (second (str/split (or (telemetry/inject-traceparent) "") #"-"))]
    (when (and id (not (re-matches #"0+" id))) id)))

(defn- record-access
  [ctx response]
  (let [{:keys [request]} ctx
        {:keys [auth access-log headers request-id request-method]} request
        {:keys [bank-id]} auth
        match (:reitit.core/match request)
        status (:status response)
        body (:body response)]
    (when (and access-log bank-id match status)
      (access-log/record
       access-log
       {:bank-id bank-id
        :request-id request-id
        :idempotency-key (get headers "idempotency-key")
        :correlation-id (or (get headers "correlation-id")
                            (get headers "idempotency-key"))
        :trace-id (trace-id)
        :requested-at (utility/now)
        :requested-by (actor/actor auth)
        :method (str/upper-case (name request-method))
        :route (:template match)
        :path-ids (or (:path-params match) {})
        :http-status status
        :problem-type (when (and (>= status 400) (map? body))
                        (some-> (:type body)
                                str))
        :replayed (= "true" (get-in response [:headers "Idempotent-Replayed"]))
        :client-address (client-address request)
        :duration (quot (- (utility/nanos) (::started ctx)) 1000)}))))

(def access-entry
  "Writes an access entry for the call once its response is chosen, or
  as a 500 when it throws: only for a caller authenticated for a bank,
  which `auth` resolves after this interceptor enters and before it
  leaves, so a call that names no bank, or one its caller may not act
  on, writes none. The entry is queued and written off the request's
  thread."
  {:name ::access-entry
   :enter (fn [ctx] (assoc ctx ::started (utility/nanos)))
   :leave (fn [ctx] (record-access ctx (:response ctx)) ctx)
   :error (fn [ctx] (record-access ctx {:status 500}) ctx)})

(def named-bank
  "Refuses with 403 a request that resolves no bank: an operator naming
  none in the `Bank-Id` header. A member's is refused before this, by
  `auth/require-bank`, and a service token always names its own."
  {:name ::named-bank
   :enter (fn [ctx]
            (if (get-in ctx [:request :auth :bank-id])
              ctx
              (sc/terminate ctx
                            (errors/forbidden-response
                             "Name the bank in the Bank-Id header"))))})

(def test-bank
  "Refuses a request whose bank is not a test bank: 404 where the bank
  does not exist, and 409 `:bank/invalid-status` where it is live. For
  the sandbox routes, which move money no scheme sent. Sits after
  `named-bank`, which resolves the bank it reads."
  {:name ::test-bank
   :enter (fn [ctx]
            (let [{:keys [record-db record-store auth]} (:request ctx)
                  bank (banks/get-bank {:record-db record-db
                                        :record-store record-store}
                                       (:bank-id auth))
                  refusal (cond (error/anomaly? bank)
                                bank

                                (not= :bank-status-test (:status bank))
                                (error/reject :bank/invalid-status
                                              {:message
                                               "Only a test bank can simulate"
                                               :bank-id (:bank-id auth)
                                               :status (:status bank)}))]
              (if refusal
                (sc/terminate ctx (errors/anomaly->response refusal))
                ctx)))})
