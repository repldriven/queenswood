# Scenario Testing Architecture

## What we're building

System-level testing for Queenswood uses **model-equality property
testing** via [fugato](https://github.com/vouch-opensource/fugato).
The approach is two parallel state machines fed the same commands,
with end states compared.

A pure-functional reimplementation of the bank's rules — the
**model** — runs alongside the real system. Fugato generates
sequences of commands. Both the model and the real system process
those sequences. Projection functions reduce real-system state to
the same shape as model state, and the property is simple equality.
When they diverge, fugato shrinks the failing sequence to a minimal
reproducer.

Hand-authored EDN scenarios use the same runner and projections
for cases we want locked down explicitly. Fugato finds bugs in
sequences nobody thought to write; EDN scenarios document specific
cases that must keep working.

## Two sibling bricks: domain layer and HTTP layer

Scenario testing lives in two bricks that share shape but target
different layers.

- **`test-scenarios`** is the **domain-layer** brick. Verbs
  call component interfaces directly (`payment/submit-outbound`,
  `balance/get-balance`, …). It owns the model-equality property
  test described in the rest of this document — fugato generates
  command sequences, the model and projections live in
  `test-model` and `test-projections`, and equality
  after each sequence is the property.
- **`test-api-scenarios`** is the **HTTP-surface** brick.
  Verbs are HTTP requests against a live `api` (booted in
  the test system). Scenarios use EDN with the same `:given` /
  `:when` / `:then` shape, refs (`[:ref alias k1 …]`) for
  capturing values across steps, and
  `nubank/matcher-combinators` markers (`[:m/regex …]`,
  `[:m/embeds …]`, …) for assertion shapes. This brick replaced
  the scattered per-base / per-component `*_test.clj` API tests
  that used to live in `bases/api/test/`,
  `components/api-key/test/`, and similar paths.

The two bricks coexist deliberately. The domain-layer brick
finds bugs in rules and choreography; the HTTP-layer brick locks
down request/response contracts, status codes, error bodies, and
hypermedia links. They share neither code nor configuration
beyond pattern — each has its own runner, its own
`application-test.yml`, and its own scenario fixtures.

### Proving idempotency over HTTP

Three things in the HTTP-layer runner exist for idempotency, where
what a client sees is the whole of the contract.

`:assert/response` takes `:headers` beside `:body`, matched the same
way, against the header map the runner captures on every response.
It is how a scenario asserts that a replayed answer is marked as one.

`:api/race` sends one request `:count` times at once, on futures, and
asserts the invariant rather than the timing. Whether a losing request
finds the winner still in flight or already committed is the
scheduler's business and cannot be pinned in a scenario; what holds
either way is that exactly `:fresh` responses carry the expected
status and no replay marker, and every other response is either a 409
saying an identical request is in flight or an exact replay of the
winner. The timing-dependent half — the 409 specifically — is pinned
with a latch in the idempotency brick's own test, where it can be.

Every `Idempotency-Key` literal in the corpus must be unique across
files. Bank creation sits in the `:given` of almost every scenario and
the admin principal is shared by the whole boot, so a literal used by
two files would replay the other file's bank rather than create one.
The runner test fails on a shared literal; a key repeated *within* one
file is how a replay is written, and is fine.

The rest of this TDD is about the domain-layer brick. The
HTTP-layer brick follows the same EDN + runner pattern with verb
semantics swapped for HTTP requests.

## Why this approach

We are deliberately writing the rules twice. The model is a
pure-functional reimplementation of the same logic that lives in
the production components. This is the cost.

The defence is that the two implementations live at very different
levels. The model is pure functions over a flat map: no FDB
transactions, no message-bus envelopes, no protobuf, no choreography,
no outbox, no auth. It will likely run to a few hundred lines for
the whole bank. The real implementation is the production system
with all its concerns. When they disagree, one of two things has
happened: the production system has a bug (most common), or the
model has a bug (also valuable — usually means the rule was fuzzy
and writing it out cleanly exposed it).

The model also doubles as executable documentation of what the
bank is _supposed_ to do, separate from how it does it. It is
small-state and rule-heavy, which is exactly the shape fugato
handles best.

## Component layout

Three test-only components, picked up by the existing test
pipeline (the `external-test-runner` base and each brick's `test/`
paths under `project:dev`). They are **not** Polylith projects —
there's no deployable artifact here. If the scenario runner ever
needs a CLI entrypoint, it'll be a `bases/test-scenarios`
base. Namespaces follow the repo's `com.repldriven.queenswood.*`
convention:

```
components/
  test-model/
    src/com/repldriven/queenswood/test_model/
      interface.clj            ; re-exports model map and helpers
      state.clj                ; init-state, helpers
      policy.clj               ; pure re-implementation of policy rules
      balances.clj             ; balance-related commands
      transfers.clj            ; transfer commands
      fees.clj                 ; fee commands
      [later: interest.clj, accrual.clj, ...]

  test-projections/
    src/com/repldriven/queenswood/test_projections/
      interface.clj            ; re-exports project-* fns
      balances.clj             ; project-balances
      [later: accounts.clj, limits.clj, ...]

  test-scenarios/
    src/com/repldriven/queenswood/test_scenarios/
      interface.clj            ; run-commands, run-scenario
      runner.clj               ; the step dispatcher
      verbs.clj                ; verb -> action mapping
      id_mapping.clj           ; model-id <-> real-id side table
      quiescence.clj           ; wait-for-catchup
      divergence.clj           ; first-divergence debug helper
```

### Dependency arrows

- `test-projections` depends on production component
  **interfaces only** (`balance/interface`,
  `cash-account/interface`, etc.). Never on internals.
- `test-model` depends on **nothing** in production. It is
  pure functions over a map. It does not import proto schemas,
  Malli contracts, nom anomalies, or anything from production
  components — including `policy`. The model carries its own
  hand-written re-implementation of the policy rules it needs
  (see `policy.clj`); when the production policy semantics change,
  the model's `policy.clj` is updated to match.
- `test-scenarios` depends on `test-model`,
  `test-projections`, and the production components it needs
  to drive (command-submission interfaces, primarily).
- The arrow only ever points test → production. If a production
  component starts importing from `test-*`, Polylith will
  yell. Listen to it.

### Why this layout

Per-component projections were considered and rejected. If
`balances` owned a `projection.clj`, that file would depend
on the model's data shapes — a test concern leaking into a
production component. Worse, property tests that assert across
multiple components would have no natural home. A dedicated
component for projections lets one place know about both sides of
the equality.

The model is similarly cross-cutting. Putting it in its own
component keeps it independently inspectable and lets it grow
incrementally — start with balances and transfers, add interest
later, add KYC restrictions later still.

## The model

The model is a map from command keyword to command spec, in the
fugato shape:

```clojure
(def model
  {:open-account      open-account-spec
   :inbound-transfer  inbound-transfer-spec
   :outbound-transfer outbound-transfer-spec
   :apply-fee         apply-fee-spec})
```

Each spec has up to five fields:

- `:run?` — `(state) -> bool`. Whether this command is eligible
  to be generated in the current state. Encodes preconditions
  ("can't transfer from an account that doesn't exist").
- `:args` — `(state) -> generator`. A
  `clojure.test.check.generators` generator returning a tuple of
  arguments for the command.
- `:freq` — integer weighting how often this command is generated
  relative to others. Defaults to 1.
- `:next-state` — `(state, command) -> state`. Pure function that
  evolves model state.
- `:valid?` — `(state, command) -> bool`. Used during shrinking
  only. When the shrinker drops earlier commands, later commands
  may no longer make sense; `:valid?` re-checks each remaining
  command against the rewound state and prunes the ones that no
  longer fit.

### What goes in the model

Domain rules. Account state. Balance arithmetic. Limit evaluation
logic (re-implemented from policies in pure-function form).
Transfer mechanics. Fee mechanics. Time advancement and interest
accrual logic. Three-way command outcomes — but encoded as state
transitions, not as proto enums (see below).

### What does not go in the model

- No FDB. State is a Clojure map.
- No message bus. Commands are plain data; `:next-state` updates state
  directly.
- No protobuf. The model uses Clojure keywords and maps;
  `:transaction-type-inbound-transfer` not
  `TRANSACTION_TYPE_INBOUND_TRANSFER`.
- No Malli or `defcontract`. The model is small enough that schema
  validation is overkill, and adding it makes the model start to
  look like production.
- No nom anomalies. When a command is rejected, `:next-state`
  returns state unchanged. The runner detects this by comparing
  `:before` and `:after` metadata fugato attaches.
- No real IDs. Model uses synthetic IDs (`:acct-0`, `:acct-1`);
  the runner maps these to the production ULID-prefixed IDs
  (`acc.<ulid>`, `org.<ulid>`, etc. — see
  `utility.interface/generate-id`).
- No external services. ClearBank, KYC providers, etc. are not
  modelled. test.contract handles those boundaries separately.

If the model starts to look like production code, the property
test stops finding bugs because the model and reality have
converged on the same wrongness. Resist the urge to make the
model "more realistic."

### Example spec

```clojure
(def inbound-transfer-spec
  {:run?       (fn [state] (seq (known-accounts state)))
   :args       (fn [state]
                 (gen/tuple (gen/elements (known-accounts state))
                            (gen/choose 1 10000)))
   :next-state (fn [state {[acct amt] :args}]
                 (let [pre  (balance state acct)
                       post (+ pre amt)]
                   (if (policy-permits? (:policies state)
                                        :inbound-transfer pre post)
                     (assoc-in state [:accounts acct :available] post)
                     state)))
   :valid?     (fn [state {[acct] :args}]
                 (contains? (:accounts state) acct))})
```

Note `:next-state` encodes both success and rejection. When
`policy-permits?` returns false, the model returns state
unchanged — the command was generated but its effect was rejected.
The real system must do the same. Equality of end states is the
property.

### Initial state

```clojure
(def init-state
  {:accounts {}                     ; acct-id -> {:available <int-pence>}
   :policies <baseline-policy-set>  ; matches policies in production
   :next-id  0
   :now      0})                    ; epoch ms; advanced by :advance-time
```

The policy set in `init-state` should mirror the baseline policies
the real system loads. When production policies change, this
changes too. Treat it as part of the model's contract with the
real system.

## The projection pattern

A projection is a function on the **real side** that reduces
real-system state to the same shape as some part of model state.
Projections have three required properties:

1. **Total over partial.** Take whatever the real system has,
   return the model-shaped subset. Ignore everything irrelevant:
   message-bus envelopes, FDB versionstamps, OpenTelemetry spans,
   audit metadata, addressing schemes. Equality should fail only
   when domain rules disagree, not when the real system has a
   field the model didn't bother modelling.

2. **Boundary-aware.** Read through the same query path the API
   uses. If `available` balance is computed by `balances`
   aggregating postings, the projection calls _that_, not
   `fdb/get`. Otherwise the projection is testing storage, not
   the system.

3. **Deterministic.** No timestamps, no generated IDs leaking
   through. Strip or normalise anything that varies run-to-run.

### Shape

```clojure
(defn project-balances
  "Reads available balance for every account known to the bank.
   Returns {model-acct-id -> int-pence}, mirroring :accounts."
  [bank id-mapping]
  (->> (cash-account/list-accounts bank)
       (map (fn [{:keys [account-id]}]
              [(get id-mapping account-id)
               (balance/available bank account-id)]))
       (into {})))

(defn project-model-balances
  "Same shape, from model state."
  [model-state]
  (-> (:accounts model-state)
      (update-vals :available)))
```

The property:

```clojure
(= (project-balances real-bank id-mapping)
   (project-model-balances model-end-state))
```

### Rules of thumb

- **One projection per assertion, not one giant projection.**
  Smaller, targeted projections give clearer failure messages and
  let assertions focus on what matters. Plan for
  `project-balances`, `project-account-statuses`,
  `project-policy-violations`, etc. Each property test picks the
  projections relevant to what it tests.

- **ID mapping lives in the runner, not in projections.**
  Projections receive the mapping as an argument. This keeps
  projections pure with respect to the side-table.

- **Projections never instantiate the real system.** They take an
  already-running bank handle and read from it. Setup and
  teardown are the runner's job.

## The runner

`test-scenarios` is a **component** (library code), not a
base. Tests that exercise the runner — Phase 3 hand-built
sequences, Phase 4 EDN scenarios, Phase 5 fugato property tests —
live in the runner brick's own `test/`, mirroring how every other
production brick tests its interface. There's nothing here to
deploy, so a project would be wrong; there's no `-main`, so a base
would be wrong.

If a CLI entrypoint to run a single scenario outside the polylith
test machinery turns out to be useful (hand-debugging, replaying a
shrunk fugato sequence one step at a time), add a thin
`bases/test-scenarios` later that wraps the component with a
`-main`. That's orthogonal to where the tests live.

`test-scenarios` owns command dispatch, ID mapping,
quiescence, and comparison.

### Command dispatch

A command from the model (or from an EDN scenario) is a map:
`{:command :inbound-transfer :args [:acct-0 5000]}`. The runner
translates this to a real command — looking up `:acct-0` in the
ID side-table, building the real command envelope, submitting
through the production command pipeline.

For commands that create entities (`:open-account`), the runner
captures the returned real ID and stashes the mapping:
`{:acct-0 "acc.01k...."}`. Subsequent commands referencing
`:acct-0` translate transparently.

### ID side-table

A plain map, owned by the runner for the duration of a single
command-sequence run. Reset between runs. Two views:

```clojure
{:model->real {:acct-0 "acc.01k..." :acct-1 "acc.02k..."}
 :real->model {"acc.01k..." :acct-0 "acc.02k..." :acct-1}}
```

Projections receive `:real->model` so they can return model IDs
in their output, making equality with model state direct.

### Quiescence wait

This is the bit that bites. The production system is CQRS —
commands go through the message bus, postings hit the outbox, projections
eventually catch up. After submitting commands, the runner must
wait until the read side has caught up before projecting.
Otherwise tests are flaky in ways that look like real bugs.

The wait is on changelog cursor catch-up to the last command's
outbox versionstamp, with a timeout. The timeout should be
generous (single-digit seconds) and timing out is a test failure.

### Five outcomes, two states

Recall the policy evaluator's outcomes: `:not-applicable`,
`:permitted`, `:permitted/curative`, `:denied/would-breach`, plus
underlying `:failed` for genuine errors. From the model's
perspective these collapse to two: state advanced, or state
unchanged. The runner doesn't need to inspect outcomes directly;
it submits the command, waits for quiescence, and projects. If
the real system rejected the command, the projection will show
unchanged state, matching the model. Equality holds.

Where the runner _does_ care about outcomes is when a command
genuinely failed (timeout, infrastructure error). That's a runner
failure, not a domain divergence, and should be surfaced
distinctly in test output.

### EDN scenarios

The runner accepts both fugato-generated command sequences and
EDN-loaded scenarios. Same dispatch, same projections, same
comparison. The only difference is the source.

EDN scenarios live under the runner brick's test-resources, at
`bank-test-scenarios/scenarios/*.edn` on the test classpath
(see `components/test-scenarios/test-resources/`). Each is
a map with `:given`, `:when`, `:then` sections, all of which are
sequences of the same step shape —
`{:command <kw> :args [...]}` — as fugato emits, plus assertion
verbs:

```clojure
{:name "Curative inbound credit permitted when in breach"
 :tags #{:policies :curative}
 :given
 [{:command :open-account     :args []}
  {:command :inbound-transfer :args [:acct-0 1000]}
  {:command :apply-fee        :args [:acct-0 1500]}]
 :when
 [{:command :inbound-transfer :args [:acct-0 200]}]
 :then
 [{:command :assert-balance   :args [:acct-0 -300]}]}
```

Schema-validate at load time using Malli, so typos fail loudly
before the runner starts.

## Fugato wiring

The property test:

```clojure
(defspec model-eq-reality 50
  (prop/for-all [commands (fugato/commands model init-state 30 1)]
    (let [{:keys [bank id-mapping]} (start-fresh-bank)
          _              (run-commands bank id-mapping commands)
          _              (wait-for-quiescence bank)
          actual         (project-balances bank
                                           (:real->model id-mapping))
          expected       (-> commands
                             last
                             meta
                             :after
                             project-model-balances)]
      (try (= actual expected)
           (finally (stop-bank bank))))))
```

The arguments to `fugato/commands`: model map, initial state,
target sequence length (30), minimum shrink length (1). Tune
these as the suite matures.

`fugato/commands` attaches `:before` and `:after` model state to
each command as Clojure metadata. Use
`(-> commands last meta :after)` to get the expected end state
without re-running the model.

### Failure debugging

When the property fails, fugato shrinks to a minimal sequence and
prints it. Useful, but doesn't tell you _which step_ diverged.
The `divergence.clj` helper walks the shrunk sequence step-by-step,
projecting after each, and returns the first index where model
and reality differ:

```clojure
(defn first-divergence [commands runner project-real project-model]
  (reduce
    (fn [_ [i cmd]]
      (let [_     (runner cmd)
            real  (project-real)
            model (-> cmd meta :after project-model)]
        (when-not (= real model)
          (reduced {:index i :command cmd :real real :model model}))))
    nil
    (map-indexed vector commands)))
```

## Async and quiescence

The single largest source of flakiness in this style of testing
is the read-side projections lagging the write side. The runner
_must_ wait before projecting. Specifically:

- After submitting a command, capture the outbox versionstamp (or
  whatever sequencing token your changelog publishes).
- Before projecting, poll the read-side cursor for that component
  until it reaches or exceeds the captured versionstamp.
- Time out after a generous limit (a few seconds is plenty in
  test). Timeout is a test failure, surfaced as a runner error,
  not a domain divergence.

Without this, intermittent failures will appear identical to real
bugs and you will lose hours chasing them. Implement the wait
before writing the first defspec.

## Considered alternatives, rejected

**Per-component projections.** Putting projection functions
inside `balances`, `accounts`, etc. Rejected because it
leaks test concerns into production paths and makes
cross-component property tests homeless. Centralised in
`test-projections`.

**Property assertions instead of model equality.** Writing
invariants like "available balance never below floor for in-scope
transactions." Rejected because banking bugs are predominantly
about state transitions, not violated invariants. Model equality
catches more bugs and the model is much easier to write than a
comprehensive invariant set.

**Stateful-check, fsm-test-check, states.** All reasonable
Clojure stateful PBT libraries. Fugato chosen for symbolic-only
generation (cheap to produce long command sequences without
burning time on argument detail) and "bring your own runner" fit
(we already need a runner for EDN scenarios; reusing it is free).

**Cucumber/Gherkin.** Considered for EDN scenarios. Rejected
because the natural-language angle is mostly a tax in an
engineering team — regex step definitions, English↔code
translation. EDN keeps the structure without the parser.

**Snapshot testing.** Considered for multi-leg outputs such as
a capitalisation run, whose per-account transaction and
aggregate ledger entry have to be checked together. Not
adopted upfront. Snapshots become noise generators when
they're too broad. May add later for very specific cases that
justify the lock-in.

## Status and follow-ups

Initial scope is the four commands `:open-account`,
`:inbound-transfer`, `:outbound-transfer`, `:apply-fee` and
`project-balances`. See `docs/plan/scenario-testing.md` for the
implementation plan.

Future expansions, in rough priority order:

- `:internal-transfer` (two-leg, exercises per-leg policy
  evaluation)
- `:advance-time` and `:accrue-interest` (accrual writes a
  balance rather than a transaction, so the assertions are on
  the run's rows and its ledger entry)
- `:reverse` (status transition; tests the curative path
  naturally)
- Multi-account and multi-party scenarios
- Multiple policies composed
- ClearBank scheme integration via test.contract (separate,
  complementary)
