(ns com.repldriven.queenswood.test-scenarios.interface
  "Drives a model command sequence (from fugato or an EDN scenario)
  against a real bank, threading a runner context through each step
  and waiting for read-side quiescence before returning. Returns the
  final context map; the caller pulls `:id-mapping` out of it to feed
  into a projection for equality checks."
  (:require
    [com.repldriven.queenswood.test-scenarios.id-mapping :as id-mapping]
    [com.repldriven.queenswood.test-scenarios.invariants :as invariants]
    [com.repldriven.queenswood.test-scenarios.quiescence :as quiescence]
    [com.repldriven.queenswood.test-scenarios.scenario :as scenario]
    [com.repldriven.queenswood.test-scenarios.verbs :as verbs]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.identity-provider.interface :as identity-provider]
    [com.repldriven.mono.utility.interface :as util]))

(defn fresh-context
  "Build the initial runner context for one command-sequence run.
  The fresh `:run-id` (a uuidv7) prefixes idempotency keys so
  multiple runs against the same bank don't collide on the dedup
  index. Counter-leg GL accounts (1100, 1200, 2400, 2500) are
  resolved per-bank at verb-dispatch time from the chart of
  accounts.

  Args:
  - bank: FDB config map (`:record-db` / `:record-store`)."
  [bank]
  {:bank bank
   :identity-provider (identity-provider/local-provider {})
   :id-mapping id-mapping/init
   :banks {}
   :products {}
   :migrations {}
   :parties {}
   :accounts {}
   :payments {}
   :next-model-id 0
   :next-bank-id 0
   :next-product-id 0
   :next-party-id 0
   :next-payment-id 0
   :next-inbound-id 0
   :run-id (str (util/uuidv7))
   :counter 0
   :outcomes []})

(defn run-commands
  "Dispatch each command in `commands` against the real bank,
  threading the runner context through. Waits for read-side
  quiescence before returning the final context.

  After every step both standing invariants fire (see
  `invariants/verify-books-tie`), so any command that leaves a bank's
  trial balance out of balance, or a control holding anything other
  than its sub-ledger's roll-up, fails the scenario at the offending
  step.

  Args:
  - ctx: runner context (typically from `fresh-context`).
  - commands: sequence of `{:command kw :args [...]}` maps."
  [ctx commands]
  (let [final (reduce (fn [ctx command]
                        (invariants/verify-books-tie (verbs/dispatch ctx
                                                                     command)))
                      ctx
                      commands)]
    (quiescence/wait (:bank final))
    final))

(defn run-scenario
  "Load and validate the EDN scenario at `resource-path`, then run
  every step through the same dispatch as `run-commands`. Returns
  the final context, or an anomaly if loading or schema validation
  fails. Assertion steps inside the scenario fire `clojure.test/is`
  on dispatch.

  Args:
  - bank: FDB config map.
  - resource-path: classpath path to the scenario EDN file."
  [bank resource-path]
  (let [loaded (scenario/from-resource resource-path)]
    (if (error/anomaly? loaded)
      loaded
      (run-commands (fresh-context bank)
                    (scenario/steps loaded)))))

(def
  ^{:doc
    "Read and validate an EDN scenario at a classpath path.
  Returns the parsed scenario map or a
  `:bank-test-scenarios/scenario` anomaly. Args:
  - resource-path: classpath path to the scenario EDN."}
  from-resource
  scenario/from-resource)

(def
  ^{:doc
    "Concatenated `:given`/`:when`/`:then` steps from a
  scenario map, in order, ready for the runner. Args:
  - scenario: parsed scenario map."}
  steps
  scenario/steps)
