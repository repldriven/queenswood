(ns com.repldriven.queenswood.idempotency.interface
  "FDB-backed idempotency cache for the bank API. Caches the response
  for any write route that requires `Idempotency-Key`, scoped by the
  authenticated principal — the bank id for a service token,
  `queenswood-admin` for the admin client, the user id for a user —
  and by the operation. Replays cached 2xx/4xx for a matching key and
  a matching request, marking the replay with `Idempotent-Replayed`;
  refuses 422 when the key is live against a different request, one
  sent under another bank included; skips 5xx so transient failures can
  be retried. A route may name response paths the cache leaves out of
  its entry, so a replay omits what only the first response carries."
  (:require
    [com.repldriven.queenswood.idempotency.system]

    [com.repldriven.queenswood.idempotency.interceptors :as interceptors]))

(def cache-response
  "Reitit interceptor that protects an idempotent route. Plug into the
  shared `:interceptors` chain after auth and after
  `server/require-idempotency-key`."
  interceptors/cache-response)

(def cache-response-omitting
  "`cache-response` for a route whose response carries what must not be
  stored. `paths` is a vector of key paths into the response body, such
  as `[[:token]]` or `[[:owner-invitation :token]]`; each is left out of
  the completed entry and so is absent from a replay, while the first
  response carries it. A path whose parent is absent from the body
  leaves the body unchanged. The interceptor carries `paths` as
  `:omitted-paths`."
  interceptors/cache-response-omitting)
