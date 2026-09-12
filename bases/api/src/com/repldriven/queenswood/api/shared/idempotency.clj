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
    retry is refused 409 `migration/invalid-status`."
   [:put "/v1/webhook-endpoints/{endpoint-id}"]
   "Absolute set. The body names the whole endpoint — address,
    description and chosen kinds — so a retry converges."
   [:post "/v1/webhook-endpoints/{endpoint-id}/enable"]
   "Source-state guard. Only a disabled or paused endpoint may be
    enabled; a retry finds it enabled and is refused 409
    `webhook-endpoint/invalid-status`. The optional `since` backfills
    only what has never been delivered, so a retry inside the same
    window adds nothing."
   [:post "/v1/webhook-endpoints/{endpoint-id}/disable"]
   "Source-state guard. Only an enabled or paused endpoint may be
    disabled; a retry finds it disabled and is refused 409
    `webhook-endpoint/invalid-status`."
   [:delete "/v1/webhook-endpoints/{endpoint-id}"]
   "Source-state guard. Only a live endpoint may be removed; a retry
    finds it removed and is refused 409
    `webhook-endpoint/invalid-status`."
   [:post "/v1/me/invitations/{invitation-id}/accept"]
   "Source-state guard. Only a pending invitation may be accepted; a
    retry finds it accepted and is refused `invitation/invalid-status`."
   [:post "/v1/me/invitations/{invitation-id}/decline"]
   "Source-state guard. Only a pending invitation may be declined; a
    retry finds it declined and is refused `invitation/invalid-status`."
   [:post "/v1/me/memberships/{membership-id}/leave"]
   "Source-state guard. Only an active membership may be ended; a retry
    finds it ended and is refused `membership/invalid-status`."
   [:post "/v1/members/{membership-id}/change-role"]
   "Absolute set. The body names the role, so a retry converges."
   [:post "/v1/members/{membership-id}/remove"]
   "Source-state guard. Only an active membership may be ended; a retry
    finds it ended and is refused `membership/invalid-status`."
   [:post "/v1/invitations/{invitation-id}/withdraw"]
   "Source-state guard. Only a pending invitation may be withdrawn; a
    retry finds it withdrawn and is refused `invitation/invalid-status`."})

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
