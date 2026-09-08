(ns com.repldriven.queenswood.test-api-scenarios.interface
  "Drives EDN scenarios of HTTP steps against a running bank API.

  A scenario is a map with `:given` / `:when` / `:then` step lists;
  each step is dispatched through `verbs/dispatch`. The runner
  context carries a base URL, an admin bearer token (a Keycloak-
  minted service JWT carrying the `admin` realm role), the realms'
  token endpoints and a test-owned signing key for the user-token
  verbs, and a `:captures` map populated by steps that capture their
  response body via `:as <alias>`. Later steps refer back to
  captures with `[:ref :alias :k1 :k2 ...]` markers.

  Scenarios call the API over HTTP. One booted system serves every
  scenario; per-scenario isolation is the fresh `:captures` map
  plus a fresh request counter."
  (:require
    [com.repldriven.queenswood.test-api-scenarios.scenario :as scenario]
    [com.repldriven.queenswood.test-api-scenarios.verbs :as verbs]

    [com.repldriven.mono.error.interface :as error]))

(defn fresh-context
  "Build the initial runner context for one scenario.

  Args (map):
  - `:base-url` — root URL of the booted bank API (e.g.
    `http://localhost:NNNN`).
  - `:admin-token` — Keycloak-minted service JWT used when
    `:auth :admin` appears in a step.
  - `:token-endpoints` (optional) — realm keyword → that realm's
    OpenID token endpoint, for `:auth/mint-user-token`. The API's
    `/oauth/token` proxies `client_credentials` only, so a user
    token is fetched from the realm directly.
  - `:signing-key` (optional) — a test-owned `java.security.KeyPair`
    for `:auth/sign-token`, which mints tokens the realm would never
    issue. The caller generates one for the whole run rather than one
    per scenario; RSA key generation is not free.
  - `:run-id` (optional) — caller-supplied tag for log lines.

  The fresh `:captures` map isolates scenarios from each other so
  one boot can serve many."
  [{:keys [base-url admin-token token-endpoints signing-key run-id]}]
  {:base-url base-url
   :admin-token admin-token
   :token-endpoints token-endpoints
   :signing-key signing-key
   :run-id run-id
   :captures {}
   :last-response nil
   :counter 0})

(defn run-commands
  "Dispatch each step in `commands` through `verbs/dispatch`,
  threading the runner context through. Assertion steps fire
  `clojure.test/is`. Returns the final context."
  [ctx commands]
  (reduce verbs/dispatch ctx commands))

(defn run-scenario
  "Load the EDN scenario at `resource-path`, then dispatch every
  step (`:given` then `:when` then `:then`). Returns the final
  context, or an anomaly if loading or schema validation fails."
  [ctx resource-path]
  (let [loaded (scenario/from-resource resource-path)]
    (if (error/anomaly? loaded)
      loaded
      (run-commands ctx (scenario/steps loaded)))))

(def
  ^{:doc
    "Read and validate an EDN scenario at a classpath path.
  Returns the parsed scenario map or a
  `:bank-test-api-scenarios/scenario` anomaly. Args:
  - resource-path: classpath path to the scenario EDN."}
  from-resource
  scenario/from-resource)

(def
  ^{:doc
    "Concatenated `:given`/`:when`/`:then` steps from a scenario
  map, in order, ready for the runner. Args:
  - scenario: parsed scenario map."}
  steps
  scenario/steps)
