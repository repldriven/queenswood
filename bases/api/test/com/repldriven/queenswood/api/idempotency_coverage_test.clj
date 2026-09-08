(ns com.repldriven.queenswood.api.idempotency-coverage-test
  "Walks the compiled route tree and holds REQ-007: every write method
  under `/v1` either declares `require-idempotency-key` immediately
  followed by `cache-response`, or names its guard in `exempt-writes`.

  The reverse holds too, so the allow-list cannot rot: every entry in
  it names a write route that exists and that does not declare the
  pair after all.

  A route that declares the pair carries a three-part contract, and
  all three parts are held here: the interceptors, the 409, 422 and
  503 `with-responses` folds into its `:responses`, and the
  `Idempotency-Key` parameter in its `:openapi`. Holding only the
  first would let a route require the header and still ship a
  document naming neither the header nor the refusals it answers.

  No system is booted. `api/router` compiles the routes from an empty
  interceptor context, and reitit has already merged the group data
  into each method by the time `reitit.core/routes` reports it."
  (:require
    [com.repldriven.queenswood.api.api :as api]
    [com.repldriven.queenswood.api.shared.idempotency :as shared.idempotency]
    [com.repldriven.queenswood.api.shared.parameters :as shared.parameters]

    [com.repldriven.queenswood.idempotency.interface :as bank-idempotency]

    [com.repldriven.mono.server.interface :as server]

    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [reitit.core :as r]))

(def ^:private write-methods [:post :put :patch :delete])

(def ^:private shared-refusals
  "The statuses `shared.idempotency/with-responses` folds into every
  route that declares the pair."
  [409 422 503])

(def ^:private pair
  [(:name server/require-idempotency-key)
   (:name bank-idempotency/cache-response)])

(defn- declares-pair?
  [method-data]
  (->> (:interceptors method-data)
       (map :name)
       (partition 2 1)
       (some #(= pair (vec %)))
       boolean))

(defn- write-routes
  "Every `[method template]` write under `/v1`, paired with the route
  data reitit compiled for that method."
  []
  (let [router (api/router {:interceptors []})]
    (for [[template data] (r/routes router)
          :when (str/starts-with? template "/v1/")
          method write-methods
          :let [method-data (get data method)]
          :when method-data]
      [[method template] method-data])))

(defn- describe
  [[method template]]
  (str (str/upper-case (name method))
       " "
       template))

(deftest every-write-declares-the-pair-or-names-a-guard-test
  (let [writes (write-routes)]
    (is (seq writes) "expected write routes under /v1")
    (doseq [[route data] writes]
      (testing (describe route)
        (is (or (declares-pair? data)
                (contains? shared.idempotency/exempt-writes route))
            (str "declares require-idempotency-key immediately followed by "
                 "cache-response, or names its guard in exempt-writes"))
        (when (declares-pair? data)
          (doseq [status shared-refusals]
            (is (contains? (:responses data) status)
                (str "documents the " status " that with-responses folds in")))
          (is (contains? (set (get-in data [:openapi :parameters]))
                         shared.parameters/ref-idempotency-key)
              "documents the Idempotency-Key parameter it requires"))))))

(deftest exempt-writes-names-live-undeclared-routes-test
  (let [writes (into {} (write-routes))]
    (is (seq shared.idempotency/exempt-writes))
    (doseq [[route guard] shared.idempotency/exempt-writes]
      (testing (describe route)
        (is (contains? writes route) "names a write route that exists")
        (is (and (string? guard) (seq guard))
            "states the guard that makes a retry safe")
        (when-let [data (get writes route)]
          (is (not (declares-pair? data))
              "is exempt, so it must not declare the pair as well"))))))
