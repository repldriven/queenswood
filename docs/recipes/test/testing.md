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

The full run is every service project's matrix plus the development
project, which is where the scenario bricks live:

```bash
clojure -M:poly test :all :dev
```

`just test-all` is that command with docker started first. Nothing
narrower is "all tests": `:all` on its own omits the development
project and so every scenario, and `project:dev` on its own tests each
brick against dev's dependency set only.

Specific bricks (one or more, colon-separated), in every project
that hosts them plus the development project:

```bash
clojure -M:poly test brick:<brick-name> :dev
clojure -M:poly test brick:balance:cash-account :dev
```

Never restrict a brick run to `project:dev` alone. The development
project carries every brick, so a test dependency a service project
lacks fails only in that project, and only when its tests run.

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

### eftest synchronization

The test runner is eftest, which runs tests in parallel out of
process. Tests that boot expensive infrastructure
(testcontainers, the message bus, FDB) should be marked with
`^:eftest/synchronized` on the namespace to keep too many from
overwhelming CPU and memory:

```clojure
(ns ^:eftest/synchronized
  com.repldriven.mono.processor.interface-test
  ...)
```

Pure-function tests don't need the marker.

## Rules

**MUST:**

- Use `clojure.test/deftest` for pure functions, and for a
  brick's own store and changelog against FDB under
  `with-test-system`.
- Use the scenario runner for system-level tests: anything that
  crosses a brick boundary or drives the command pipeline.
- Pin the HTTP contract as an EDN scenario in
  `test-api-scenarios`, never in a brick's `interface_test.clj`.
- Run a brick's tests with `brick:<name> :dev`, in every project
  that hosts it, never in `project:dev` alone.
- Manage system lifecycle in tests with `with-test-system`.
- Mark namespaces that boot infrastructure with
  `^:eftest/synchronized`.
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

eftest synchronization is about resource starvation. Spinning
up ten testcontainers in parallel doesn't make tests faster —
it makes them flakier. The synchronized marker keeps
parallelism on cheap tests where it actually helps.

## References

- [ADR-0009](../../adr/0009-model-equality-property-testing.md) —
  Model-equality property testing
- [docs/tdd/scenario-testing.md](../../tdd/scenario-testing.md)
- [error-handling.md](../code/error-handling.md)
- [system-configurations.md](../code/system-configurations.md)
- `test-system` brick (provides `with-test-system`, `nom-test>`)
- `test-resources` brick (shared fixtures and schemas)
