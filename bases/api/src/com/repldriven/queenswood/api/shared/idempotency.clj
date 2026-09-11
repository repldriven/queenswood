(ns com.repldriven.queenswood.api.shared.idempotency
  "The write routes that carry no idempotency pair, and the responses
  every route that carries it advertises.

  `exempt-writes` is the allow-list: a write method under `/v1` either
  declares `server/require-idempotency-key` immediately followed by
  `idempotency/cache-response`, or it appears here with the guard that
  makes a retry safe. `idempotency-coverage-test` walks the compiled
  router and holds both halves of that sentence — a route in neither
  place fails, and an entry here naming a route that does not exist,
  or one that declares the pair after all, fails too.

  `with-responses` folds the shared refusals into a route's own
  `:responses`. It deep-merges, so a route that already documents a
  409 of its own keeps that example beside the shared one."
  (:require
    [com.repldriven.queenswood.api.examples :as examples]

    [com.repldriven.queenswood.api-schema.interface :refer [ErrorResponse]]

    [com.repldriven.mono.utility.interface :as utility]))

(def exempt-writes
  "Write methods under `/v1` that carry no idempotency pair, keyed by
  the `[method template]` pair `reitit.core/routes` reports, and
  valued by the guard that makes a retry safe.

  Two shapes of guard appear. An absolute set converges: the request
  names the state it wants rather than a delta, so replaying it lands
  on the same value. A source-state guard refuses: the second attempt
  finds the entity has left the state the transition starts from and
  is rejected with a 409, which is the same answer a replayed cache
  entry would have given."
  {[:post "/v1/banks/{bank-id}/change-tier"]
   "Absolute set. The body names the tier, so a retry converges."
   [:post "/v1/banks/{bank-id}/change-status"]
   "Absolute set. The body names the status, so a retry converges."
   [:put "/v1/jobs/{job-id}/schedule"]
   "Absolute set. The body names the whole schedule, so a retry
    converges."
   [:post "/v1/cash-account-products/{product-id}/versions"]
   "Source-state guard. A second open finds the draft the first one
    created and is rejected 409 `product/draft-already-exists`; the
    handler also reads the optional key back off the store index."
   [:put "/v1/cash-account-products/{product-id}/versions/{version-id}"]
   "Absolute set. The body names the whole draft, so a retry
    converges, and a draft that has since published is refused 409
    `product/version-immutable`."
   [:delete "/v1/cash-account-products/{product-id}/versions/{version-id}"]
   "Source-state guard. Only a draft may be discarded; a retry finds
    no draft and is refused 409 `product/version-immutable`."
   [:post
    "/v1/cash-account-products/{product-id}/versions/{version-id}/publish"]
   "Source-state guard. Only a draft may be published; a retry finds
    the version published and is refused 409
    `product/version-immutable`."
   [:post "/v1/cash-account-migrations/{migration-id}/approve"]
   "Source-state guard. Only an authored migration may be approved; a
    retry is refused 409 `migration/invalid-status`."
   [:post "/v1/cash-account-migrations/{migration-id}/cancel"]
   "Source-state guard. Only a live migration may be cancelled; a
    retry is refused 409 `migration/invalid-status`."})

(def ^:private shared-responses
  {400 (ErrorResponse [#'examples/BadRequest #'examples/MissingIdempotencyKey
                       #'examples/InvalidIdempotencyKey])
   409 (ErrorResponse [#'examples/IdempotentRequestInFlight])
   422 (ErrorResponse [#'examples/IdempotencyKeyReused])
   503 (ErrorResponse [#'examples/IdempotencyCacheUnavailable])})

(defn with-responses
  "Fold the refusals every protected route shares into `responses`:
  the two `Idempotency-Key` header 400s beside the generic one, the
  409 raised while an identical request is still in flight, the 422
  refusing a key already live against a different request, and the
  503 answering a cache that could not be read."
  [responses]
  (utility/deep-merge responses shared-responses))
