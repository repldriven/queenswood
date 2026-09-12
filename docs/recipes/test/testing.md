# Testing

<!-- tessl-plugin: idioms -->

## Problem

You want to write a test for some part of Queenswood.

## Solution

We use two test forms, chosen by what's being tested:

- **`clojure.test/deftest`** for a brick's own logic: pure
  functions exercised by passing values and asserting on the
  return, and the brick's own store and changelog against a real
  FDB under `with-test-system`.
- **The scenario runner** for system-level behaviour — anything
  that crosses a brick boundary: the command pipeline, a fixture
  another write brick has to put on record, or multi-component
  interaction. Fugato property tests and
  hand-authored EDN scenarios share the same runner. Two
  sibling bricks split the layer: `test-scenarios` drives
  the domain via component interfaces (and owns the
  model-equality property test); `test-api-scenarios`
  drives the HTTP surface via real `api` requests.

System tests manage lifecycle explicitly with `with-test-system`
— not `use-fixtures` — and assert anomaly-freeness with
`nom-test>`.

### Running tests

The default is `just test`, which is what the Gas City lane runs:

```bash
clojure -M:poly test project:dev
```

It tests the bricks changed since the last `stable-*` tag in the
development project, which carries every brick. A brick counts as
changed when any of its files changed, or when a brick it depends on,
directly or through others, changed, so a change to `schema` or `fdb`
runs nearly everything. A change to the record meta-data declaration
alone selects nothing: it lives in `resources`, a brick nothing
depends on in polylith's graph.

The full suite is `just test-all`: the default, then, whatever
changed, the start-up check in every service project and the
migrator's tests, its schema-evolution guard among them, in
migrator-service:

```bash
clojure -M:poly test brick:test-startup:migrator :all
```

`brick:` with `:all` forces those bricks, and without `:dev` the
development project is skipped. `test-startup` is a test-only
component every service project pulls in through its `:test` alias,
beside `test-resources` and `testcontainers`, so poly runs its one
test once per project on that project's classpath. The test finds its
project from where `application.yml` sits on the classpath, loads the
entry base the project is named after, parses that config with the
default profile and builds the system definitions without starting
anything, so a library the project lacks, a require missing from a
base's `system.clj`, or a component-kind nothing registered fails
there in seconds. In the development project no such config is on the
classpath and the test has nothing to check.

Every brick in every service project, the per-project matrix, is CI's
run and has no recipe:

```bash
clojure -M:poly test :all :dev
```

What it adds to the full suite is each brick's tests against every
service project's own resolution of library versions.

One or more bricks, whether changed or not, in the development
project:

```bash
clojure -M:poly test project:dev brick:<brick-name> :all
clojure -M:poly test project:dev brick:balance:cash-account :all
```

No test recipe starts docker; `just docker-start` does, once.

### Choosing a test form

- **Pure function, deterministic** → `deftest`.
- **The brick's own store or changelog against FDB** → `deftest`
  under `with-test-system`, reading back through the brick's
  query sibling.
- **Needs a party, a product version, a policy or a balance on
  record first** → scenario runner. Driving another write brick
  to build a fixture is the boundary, however small the case.
- **Cross-component behaviour** → scenario runner.
- **Specific case to lock down explicitly** → EDN scenario.
- **Exploring command sequences for bugs** → fugato property
  test in `test-scenarios`.
- **HTTP contract — status codes, error bodies, hypermedia
  links, auth boundaries on the public API** → EDN scenario in
  `test-api-scenarios`, not a brick `interface_test.clj`.

Don't write `deftest`-style integration tests against the
command pipeline. The scenario runner is the only sanctioned
path for system-level tests. See
[ADR-0009](../../adr/0009-model-equality-property-testing.md) and
[docs/tdd/scenario-testing.md](../../tdd/scenario-testing.md)
for the architecture.

### What a brick's tests may require

A brick's test tree may require test infrastructure (`fdb`,
`testcontainers`, `schema`, `changelog-relay`, the `test-*`
bricks), any `*-query` brick, and the bricks the brick's own
`src` already requires. Anything else means the test has grown
into a scenario: move the case rather than adding the component
to a service project's `:test` alias so the namespace loads.

The pre-commit hook (`scripts/hooks/enforce-idioms.sh`, check
`brick-test-scope`) fails on such a require. The rare sanctioned
exception carries
`;; enforce-idioms: brick-test-scope -- <reason>` on the line
above the require, with the reason on that one line. Polylith's
`poly check` validates `src` requires only, which is why the
breach otherwise surfaces in CI, when the service project that
lacks the component runs its tests.

### with-test-system

`with-test-system` starts a test system from a YAML config,
asserts it started cleanly, and stops it after the body — no
`use-fixtures` ceremony, no `try`/`finally`, no global state.

```clojure
;; Simple form
(with-test-system
  [sys "classpath:my-component/application-test.yml"]
  (let [component (system/instance sys [:path :to :component])]
    ;; test body
    ))

;; With patch-fn — inject a handler or override a component
;; before the system starts
(with-test-system
  [sys ["classpath:server/application-test.yml"
        (fn [defs] (assoc-in defs [:system/defs :server :handler] app))]]
  ;; test body
  )
```

The optional second element of the binding vector is a patch-fn
applied to the system defs before start. Use it to inject HTTP
handlers — analogous to base-level required-component injection;
see [system-configurations.md](../code/system-configurations.md) — or to
swap a component for a test double.

### nom-test>

`nom-test>` chains operations as let-style bindings, failing
fast on any anomaly and asserting no anomaly occurred. Use `_`
for bindings whose values are only needed for `is` assertions:

```clojure
(nom-test> [result1 (operation1)
            _       (is (= expected result1))
            result2 (operation2 result1)
            _       (is (some? result2))])
```

For a single anomaly check with no further bindings:

```clojure
(nom-test> [_ (operation-that-must-not-fail)])
```

See [error-handling.md](../code/error-handling.md) for the broader
anomaly story.

### Test resources

Each brick that boots a system in tests has its own
`test-resources/<brick>/application-test.yml`. Shared test
configuration (common fixtures, common schemas) lives in the
`test-resources` brick.

The classpath URL pattern
`classpath:<brick>/application-test.yml` is what
`with-test-system` expects; load mechanics are covered by
[system-configurations.md](../code/system-configurations.md).

### The runner's parallelism, and what bounds it

The test runner is eftest, run out of process by mono's
`external-test-runner`. It runs namespaces in parallel, and the vars
within each namespace in parallel on a pool sized by the JVM's
processor count. Two settings bound what that boots:

- `with-test-system` holds one of `TEST_SYSTEM_PERMITS` permits from
  start to stop, so at most that many test systems, each with its own
  FDB container, are up at once in the JVM. Unset, nothing waits.
- `JDK_JAVA_OPTIONS=-XX:ActiveProcessorCount=<n>` caps every pool
  that sizes itself from the processor count.

`just test` and `just test-all` set both to Docker's CPU count. A raw
`clojure -M:poly test` needs them set the same way, or a run against a
Docker VM with fewer CPUs than the host can stop making progress
without failing.

`^:eftest/synchronized` on a namespace runs that file's vars one at a
time. It is for a file whose tests share state, such as a `with-redefs`
of one var across tests or a global HTTP fake, and not for a file that
boots infrastructure, which the permit bounds:

```clojure
(ns ^:eftest/synchronized
  com.repldriven.queenswood.api.auth-test
  ...)
```

## Rules

**MUST:**

- Use `clojure.test/deftest` for pure functions, and for a
  brick's own store and changelog against FDB under
  `with-test-system`.
- Use the scenario runner for system-level tests: anything that
  crosses a brick boundary or drives the command pipeline.
- Pin the HTTP contract as an EDN scenario in
  `test-api-scenarios`, never in a brick's `interface_test.clj`.
- Run `just test` as the default, the changed bricks in the
  development project, and `just test-all` as the full suite: the
  default plus the start-up check of every service project and the
  migrator's guard, whatever changed.
- Run one brick with `project:dev brick:<name> :all`.
- Manage system lifecycle in tests with `with-test-system`.
- Set `TEST_SYSTEM_PERMITS` and the processor cap as `just test` does
  when running `clojure -M:poly test` directly.
- Mark a namespace whose tests share state, such as a `with-redefs`,
  with `^:eftest/synchronized`; a namespace that only boots
  infrastructure carries no marker.
- Inject a collaborator rather than `with-redefs` a var another
  namespace calls: the redefinition is JVM-wide, and namespaces run in
  parallel whatever the marker says.
- Place per-brick test config at
  `test-resources/<brick>/application-test.yml`.
- Use `nom-test>` for assertions over anomaly-returning calls.

**MUST NOT:**

- Require another write brick's interface from a brick's tests
  to build a fixture. A test that needs a party, a product
  version or a policy on record is a scenario. In scope without
  a marker: `fdb`, `testcontainers`, `schema`, `changelog-relay`,
  the `test-*` bricks, any `*-query` brick, and what the brick's
  own `src` requires; anything else carries
  `;; enforce-idioms: brick-test-scope -- <reason>` on the line
  above the require.
- Add a component to a service project's `:test` alias so a
  brick's test namespace loads. Narrow the test instead.
- Use `use-fixtures` for system lifecycle.
- Write `deftest`-style integration tests against the command
  pipeline.
- Put projections inside production components — they live in
  `test-projections`; the dependency arrow points
  test → production, never the reverse.
- Have the test model talk to FDB, the message bus, or any
  real infrastructure. The model is pure functions over a
  Clojure map.
- Compare full real-system state to full model state. Use
  targeted projections per assertion (`project-balances`,
  `project-account-statuses`, and so on).
- Skip the quiescence wait in the scenario runner. The CQRS
  read-side lags the write-side; without the explicit wait,
  tests flake in ways that look like real bugs.
- Enrich the model to make it "more realistic." Importing
  proto schemas, Malli contracts, or production component
  interfaces into `test-model` defeats the property test.

## Discussion

The two-test-forms split keeps fast tests fast and slow tests
predictable. `deftest` for pure functions is cheap, parallel,
and doesn't need infrastructure. The scenario runner is
deliberately heavier — it boots real components — and is the
only place where system-level guarantees are established.

The scope rule for a brick's tests is what keeps the split
honest. A test that drives another write brick to put a party or
a product on record has quietly become a scenario, and it now
depends on that brick being in every project that hosts the
brick under test, which polylith does not check for test code.
The symptom is a namespace that loads in the development project
and fails in a service project; the fix is to move the case, not
to widen the project.

`with-test-system` over `use-fixtures` is a deliberate choice.
Fixtures encourage hidden global state, are surprisingly hard
to compose, and don't play well with anomaly-returning startup
code. Explicit `with-test-system` makes lifecycle visible at
every test, composes with `nom-test>`, and supports patch-fns
for handler injection cleanly.

The scenario "don'ts" are about preserving the property test's
ability to find bugs. If the model imports proto, Malli, or
production interfaces, the model and reality converge on the
same wrongness and the property stops finding bugs. If
projections leak into production code, the test concern leaks
into production. Both rules look pedantic; both pay off when
a real bug surfaces and the property finds it.

The permit is about resource starvation. Spinning up ten FDB
containers at once doesn't make tests faster — it makes them
flakier, and against a Docker VM with fewer CPUs than the JVM sees
the run stops making progress without failing. A bound taken where
the system is booted counts exactly what needs bounding, whatever
the runner's mode. The synchronized marker serialises a file's vars,
which only shared state needs; a file marked to bound its boots
runs slower for nothing. It serialises nothing beyond that file: a
`with-redefs` of a var another namespace calls, an interface fn a
handler reaches or `fdb/merge-scan` under a paging test, is visible
to that namespace's tests while they run alongside, and the marker
cannot stop it. An injected collaborator can.

## References

- [ADR-0009](../../adr/0009-model-equality-property-testing.md) —
  Model-equality property testing
- [docs/tdd/scenario-testing.md](../../tdd/scenario-testing.md)
- [error-handling.md](../code/error-handling.md)
- [system-configurations.md](../code/system-configurations.md)
- `test-system` brick (provides `with-test-system`, `nom-test>`)
- `test-resources` brick (shared fixtures and schemas)
