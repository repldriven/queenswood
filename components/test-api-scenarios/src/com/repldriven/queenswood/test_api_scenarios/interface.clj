(ns com.repldriven.queenswood.test-api-scenarios.interface
  "Drives EDN scenarios of HTTP steps against a running bank API.

  A scenario is a map with `:given` / `:when` / `:then` step lists;
  each step is dispatched through `verbs/dispatch`. The runner
  context carries a base URL, an admin bearer token (a Keycloak-
  minted service JWT carrying the `admin` realm role), the realms'
  token endpoints and a test-owned signing key for the user-token
  verbs, and a `:captures` map populated by steps that capture their
  response body via `:as <alias>`. Later steps refer back to
  captures with `[:ref :alias :k1 :k2 ...]` markers. It also carries
  a `:banks` map of every bank a step created, each with a
  bank-scoped token, and a `:skipped-banks` list of the ones no token
  could be minted for — what the standing invariants read and what
  they could not.

  Scenarios call the API over HTTP. One booted system serves every
  scenario; per-scenario isolation is the fresh `:captures` map, and
  the run id and step counter every generated idempotency key is made
  of."
  (:require
    [com.repldriven.queenswood.test-api-scenarios.invariants :as invariants]
    [com.repldriven.queenswood.test-api-scenarios.scenario :as scenario]
    [com.repldriven.queenswood.test-api-scenarios.verbs :as verbs]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.utility.interface :as utility]))

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
  - `:mail-url` (optional) — the mail catcher's API URL, for
    `:mail/await-invitation`.
  - `:payment-simulator-url` (optional) — root URL of the booted
    payment provider's simulator, which a step's `:base :payment-simulator`
    sends its request to, as `/simulate/inbound-payment` needs.
  - `:zyphe-simulator-url` (optional) — root URL of the booted
    identity-provider simulator, whose decision route a verification
    step posts the person's answers to.
  - `:providers` (optional) — the providers, a map from kind to key,
    that a bank created naming none is created on.
  - `:key-suffix` (optional) — appended to every idempotency key, so a
    run of the same scenarios on another provider in one boot replays
    none of the first run's requests.
  - `:run-id` (optional) — identifies this execution of a scenario.
    A write naming no idempotency key is sent under one made of it
    and the step's number, so it is unique to the execution. One is
    generated when absent.
  - `:await-timeout-ms` (optional) — how long a step waiting on the
    system waits before failing, unless the step names its own.
  - `:receiver` (optional) — the webhook receiver, as `{:url
    :received :answers}`: its root URL, an atom of every request it has
    been sent, and an atom of the status it answers at each path, for
    the `:webhook/*` verbs.

  The fresh `:captures` map isolates scenarios from each other so
  one boot can serve many, and the fresh `:banks` map limits the
  standing invariants to the banks this scenario created."
  [{:keys [base-url admin-token token-endpoints signing-key mail-url
           payment-simulator-url zyphe-simulator-url providers key-suffix
           run-id await-timeout-ms receiver]}]
  {:base-url base-url
   :providers providers
   :key-suffix key-suffix
   :payment-simulator-url payment-simulator-url
   :zyphe-simulator-url zyphe-simulator-url
   :mail-url mail-url
   :admin-token admin-token
   :token-endpoints token-endpoints
   :signing-key signing-key
   :run-id (or run-id (str (utility/uuidv7)))
   :await-timeout-ms await-timeout-ms
   :receiver receiver
   :captures {}
   :banks {}
   :skipped-banks []
   :last-response nil
   :counter 0})

(defn run-commands
  "Dispatch each step in `commands` through `verbs/dispatch`,
  threading the runner context through. Assertion steps fire
  `clojure.test/is`. Returns the final context.

  After every step the two standing accounting invariants fire (see
  `invariants/verify-books-tie`) against every bank the run holds a
  token for, so a step that leaves a bank's trial balance out of
  balance, or a control out of step with the sub-ledger it controls,
  fails the scenario at the offending step."
  [ctx commands]
  (reduce (fn [ctx command]
            (invariants/verify-books-tie
             (verbs/dispatch (update ctx :counter inc) command)))
          ctx
          commands))

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
  Fixture steps are expanded into the fixtures' own steps. Returns
  the parsed scenario map or a `:test-api-scenarios/scenario`
  anomaly. Args:
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

(defn scenario-files
  "Every scenario EDN file on the classpath, under
  `test-api-scenarios/scenarios/`, as `{:file :relative}` maps sorted by
  their path relative to that directory."
  []
  (scenario/resource-files scenario/scenarios-dir))

(defn scenario-resource
  "The classpath path of the scenario at `relative`, as `scenario-files`
  names it."
  [relative]
  (str scenario/scenarios-dir "/" relative))

(defn fixture-files
  "Every fixture EDN file on the classpath, under
  `test-api-scenarios/fixtures/`, as `{:file :relative}` maps."
  []
  (scenario/resource-files scenario/fixtures-dir))

(def
  ^{:doc
    "Read and validate the fixture named by keyword `fixture`. Returns
  the fixture map or a `:test-api-scenarios/scenario` anomaly. Args:
  - fixture: the fixture's name, its file's name without `.edn`."}
  fixture
  scenario/load-fixture)
