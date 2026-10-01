(ns com.repldriven.queenswood.test-scenarios.interface
  "Drives a command sequence, from fugato or an EDN scenario, against a
  real bank, threading a runner context through each step and checking
  both standing invariants after every step that changes state.

  A compared scenario runs beside the model and compares their
  projections after every step, stopping at the first divergence; a
  reality-only scenario runs without it. What went wrong comes back as
  data — a divergence, invariant failures, runner errors — for the
  caller to assert on or to falsify a property trial with."
  (:require
    [com.repldriven.queenswood.test-scenarios.divergence :as divergence]
    [com.repldriven.queenswood.test-scenarios.observer :as observer]
    [com.repldriven.queenswood.test-scenarios.policies :as policies]
    [com.repldriven.queenswood.test-scenarios.projection :as projection]
    [com.repldriven.queenswood.test-scenarios.run :as run]
    [com.repldriven.queenswood.test-scenarios.runner :as runner]
    [com.repldriven.queenswood.test-scenarios.scenario :as scenario]
    [com.repldriven.queenswood.test-scenarios.verbs :as verbs]))

(defn fresh-context
  "Build the initial runner context for one command-sequence run.
  The fresh `:run-id` (a uuidv7) prefixes idempotency keys so
  multiple runs against the same bank don't collide on the dedup
  index. Counter-leg GL accounts (1100, 1200, 2400, 2500) are
  resolved per-bank at verb-dispatch time from the chart of
  accounts.

  The verbs that read a Kafka topic (`:assert-scheme-commands`,
  `:assert-dead-lettered`) need the observers; without them those
  assertions fail.

  Args:
  - bank: FDB config map (`:record-db` / `:record-store`), carrying the
    `:bus` and `:schemas` the payment verbs publish with, the
    `:payment-providers` instance whose default provider an outbound
    payment is checked against and sent to, and the `:idv-providers`
    instance a verification is opened through.
  - observers (optional map):
    - `:scheme-commands` — an observer, from `start-observer`, of
      `topic-modulr-payment-command`.
    - `:dead-letters` — an observer of
      `topic-schemes-payments-event-dlq`.
    - `:envelope-schemas` — the serde the Kafka envelopes are written
      with, `\"command\"` and `\"event\"`.
  - opts (optional map):
    - `:await-timeout-ms` — how long any step waits for reality to reach
      the state it expects before the step is recorded `:timed-out`.
    - `:model-init` — the model state a compared scenario starts from,
      from `model-init`."
  ([bank] (runner/fresh-context bank {} {}))
  ([bank observers] (runner/fresh-context bank observers {}))
  ([bank observers opts] (runner/fresh-context bank observers opts)))

(defn model-init
  "The model's initial state held to the policies the rig at
  `config-file` boots: the platform policy every bank is held to, and
  the tier policy scenario banks are created on. Returns the state, or
  an anomaly where the configuration cannot be read.

  Args:
  - config-file: the rig's configuration, as `with-test-system` takes
    it."
  [config-file]
  (policies/model-init config-file))

(defn start-observer
  "Collect every record on a Kafka consumer's topics, from the earliest
  offset, until `stop-observer`. The records are kept as the raw bytes
  that arrived. One observer serves a whole run: a stopped consumer is
  closed and cannot be read again in the same system.

  Args:
  - consumer: a started `kafka/consumer` component bound to no bus.

  Returns the observer, for `fresh-context`."
  [consumer]
  (observer/start consumer))

(defn stop-observer
  "Stop an observer's consumer. Call it before the system stops. Returns
  nil.

  Args:
  - observer: from `start-observer`."
  [observer]
  (observer/stop observer))

(defn run-commands
  "Dispatch each command in `commands` against the real bank, threading
  the runner context through and checking both standing invariants
  after each one that changes state. Stops at a step that timed out.
  Returns the final context, whose `:invariant-failures` holds
  `{:index :command :failures}` for each step that broke one and whose
  `:runner-errors` holds what a timed-out step waited for.

  Args:
  - ctx: runner context (typically from `fresh-context`).
  - commands: sequence of `{:command kw :args [...]}` maps."
  [ctx commands]
  (runner/run-commands ctx commands))

(defn first-divergence
  "Run `commands` beside the model from its initial state, comparing
  their projections after every step. Returns `{:ctx :model}` with a
  `:divergence` of `{:index :step :only-model :only-reality}` naming
  the first step after which they differ, if one does. Stops there, or
  at a step that timed out.

  Args:
  - ctx: runner context, fresh.
  - commands: sequence of `{:command kw :args [...]}` maps, every one a
    `:model`, `:fixture`, `:read` or `:assert` verb.
  - init-state (optional): the model state to start from, its initial
    state by default."
  ([ctx commands] (divergence/walk ctx commands))
  ([ctx commands init-state] (divergence/walk ctx commands init-state)))

(defn run-scenario
  "Run a loaded scenario: beside the model when it is compared, without
  it when it is reality-only. Returns `{:ctx :compared? :divergence
  :invariant-failures :runner-errors}`, the last three nil when nothing
  went wrong.

  Args:
  - ctx: runner context, fresh.
  - loaded: a scenario from `from-resource`."
  [ctx loaded]
  (run/run-scenario ctx loaded))

(defn trial-failure
  "Why a property trial of `commands` fails, or nil when it holds: run
  against reality from `ctx`, a step timed out, a standing invariant
  broke after a step, or the end state reality projects differs from
  the model's, run from the context's `:model-init`. Returns
  `{:runner-errors}`, `{:invariant-failures}`, `{:end-states-differ
  true}` or nil.

  Args:
  - ctx: runner context, from `fresh-context` with `:model-init`.
  - commands: sequence of `{:command kw :args [...]}` maps."
  [ctx commands]
  (run/trial-failure ctx commands))

(defn projected-real
  "The real side of every projection pair, for the run `ctx` drove.
  Args:
  - ctx: runner context after a run."
  [ctx]
  (projection/real (:bank ctx) ctx))

(defn projected-model
  "The model side of every projection pair. Args:
  - state: model state."
  [state]
  (projection/model state))

(defn scenario-files
  "Every scenario EDN file on the classpath, under
  `test-scenarios/scenarios/`, as `{:file :relative}` maps sorted by
  their path relative to that directory."
  []
  (scenario/resource-files))

(defn scenario-resource
  "The classpath path of the scenario at `relative`, as `scenario-files`
  names it."
  [relative]
  (str scenario/scenarios-dir "/" relative))

(def
  ^{:doc
    "Read and validate an EDN scenario at a classpath path. Returns the
  parsed scenario map, or a `:test-scenarios/scenario` anomaly when it
  fails its schema or is compared yet names a `:reality` verb. Args:
  - resource-path: classpath path to the scenario EDN."}
  from-resource
  scenario/from-resource)

(def
  ^{:doc
    "Validate a scenario map read from `resource-path`, as
  `from-resource` does after reading it. Args:
  - resource-path: where the scenario came from, for the anomaly.
  - parsed: the scenario map."}
  parse
  scenario/parse)

(def
  ^{:doc
    "Every verb the runner dispatches, to `{:kind :args}`: its kind,
  one of `:model`, `:fixture`, `:reality`, `:read` and `:assert`, and
  the Malli schema of its arguments."}
  verbs
  scenario/verbs)

(defn verb-methods
  "The verbs the runner's dispatch has a method for."
  []
  (verbs/dispatched))

(def
  ^{:doc
    "Concatenated `:given`/`:when`/`:then` steps from a
  scenario map, in order, ready for the runner. Args:
  - scenario: parsed scenario map."}
  steps
  scenario/steps)
