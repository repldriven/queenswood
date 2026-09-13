# Testing

<!-- tessl-plugin: idioms -->

## Problem

You want to write a test for some part of Queenswood.

## Solution

We use two test forms, chosen by what's being tested:

- **`clojure.test/deftest`** for a brick's own logic: pure functions
  exercised by passing values and asserting on the return, and the
  brick's own store and changelog against a real FDB under
  `with-test-system`.
- **The scenario runner** for system-level behaviour — anything that
  crosses a brick boundary: the command pipeline, a fixture another
  write brick has to put on record, or multi-component interaction.
  Fugato property tests and hand-authored EDN scenarios share the same
  runner. Two sibling bricks split the layer: `test-scenarios` drives
  the domain via component interfaces (and owns the model-equality
  property test); `test-api-scenarios` drives the HTTP surface via real
  `api` requests.

System tests manage lifecycle explicitly with `with-test-system` — not
`use-fixtures` — and assert anomaly-freeness with `nom-test>`. Both are
mono's `test-system` brick, and [test-system](test-system.md), mono's
recipe, covers them, the per-brick `application-test.yml`, and what
bounds the runner's parallelism.

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
alone selects nothing: it lives in `resources`, a brick nothing depends
on in polylith's graph.

The full suite is `just test-all`: the default, then, whatever changed,
the start-up check in every service project and the migrator's tests,
its schema-evolution guard among them, in migrator-service:

```bash
clojure -M:poly test brick:test-startup:migrator :all
```

`brick:` with `:all` forces those bricks, and without `:dev` the
development project is skipped. `test-startup` is a test-only component
every service project pulls in through its `:test` alias, beside
`test-resources` and `testcontainers`, so poly runs its one test once
per project on that project's classpath; see
[service-configurations](../code/service-configurations.md) for what it
checks. In the development project no such config is on the classpath
and the test has nothing to check.

Every brick in every service project, the per-project matrix, is CI's
run and has no recipe:

```bash
clojure -M:poly test :all :dev
```

What it adds to the full suite is each brick's tests against every
service project's own resolution of library versions.

One or more bricks, whether changed or not, in the development project:

```bash
clojure -M:poly test project:dev brick:<brick-name> :all
clojure -M:poly test project:dev brick:balance:cash-account :all
```

`just test` and `just test-all` cap the JVM and set
`TEST_SYSTEM_PERMITS` to Docker's CPU count; a raw `clojure -M:poly
test` needs both set the same way. No test recipe starts docker;
`just docker-start` does, once.

### Choosing a test form

- **Pure function, deterministic** → `deftest`.
- **The brick's own store or changelog against FDB** → `deftest` under
  `with-test-system`, reading back through the brick's query sibling.
- **Needs a party, a product version, a policy or a balance on record
  first** → scenario runner. Driving another write brick to build a
  fixture is the boundary, however small the case.
- **Cross-component behaviour** → scenario runner.
- **Specific case to lock down explicitly** → EDN scenario.
- **Exploring command sequences for bugs** → fugato property test in
  `test-scenarios`.
- **HTTP contract — status codes, error bodies, hypermedia links, auth
  boundaries on the public API** → EDN scenario in
  `test-api-scenarios`, not a brick `interface_test.clj`.

Don't write `deftest`-style integration tests against the command
pipeline. The scenario runner is the only sanctioned path for
system-level tests. See
[ADR-0009](../../adr/0009-model-equality-property-testing.md) and
[docs/tdd/scenario-testing.md](../../tdd/scenario-testing.md) for the
architecture.

### What a brick's tests may require

A brick's test tree may require test infrastructure (`fdb`,
`testcontainers`, `schema`, `changelog-relay`, the `test-*` bricks),
any `*-query` brick, and the bricks the brick's own `src` already
requires. Anything else means the test has grown into a scenario: move
the case rather than adding the component to a service project's
`:test` alias so the namespace loads.

The pre-commit hook (`scripts/hooks/enforce-idioms.sh`, check
`brick-test-scope`) fails on such a require. The rare sanctioned
exception carries `;; enforce-idioms: brick-test-scope -- <reason>` on
the line above the require, with the reason on that one line.
Polylith's `poly check` validates `src` requires only, which is why the
breach otherwise surfaces in CI, when the service project that lacks
the component runs its tests.

## Rules

**MUST:**

- Use `clojure.test/deftest` for pure functions, and for a brick's own
  store and changelog against FDB under `with-test-system`.
- Use the scenario runner for system-level tests: anything that crosses
  a brick boundary or drives the command pipeline.
- Pin the HTTP contract as an EDN scenario in `test-api-scenarios`,
  never in a brick's `interface_test.clj`.
- Run `just test` as the default, the changed bricks in the development
  project, and `just test-all` as the full suite: the default plus the
  start-up check of every service project and the migrator's guard,
  whatever changed.
- Run one brick with `project:dev brick:<name> :all`.

**MUST NOT:**

- Require another write brick's interface from a brick's tests to build
  a fixture. A test that needs a party, a product version or a policy
  on record is a scenario. In scope without a marker: `fdb`,
  `testcontainers`, `schema`, `changelog-relay`, the `test-*` bricks,
  any `*-query` brick, and what the brick's own `src` requires;
  anything else carries `;; enforce-idioms: brick-test-scope -- <reason>`
  on the line above the require.
- Add a component to a service project's `:test` alias so a brick's
  test namespace loads. Narrow the test instead.
- Write `deftest`-style integration tests against the command pipeline.
- Put projections inside production components — they live in
  `test-projections`; the dependency arrow points test → production,
  never the reverse.
- Have the test model talk to FDB, the message bus, or any real
  infrastructure. The model is pure functions over a Clojure map.
- Compare full real-system state to full model state. Use targeted
  projections per assertion (`project-balances`,
  `project-account-statuses`, and so on).
- Skip the quiescence wait in the scenario runner. The CQRS read-side
  lags the write-side; without the explicit wait, tests flake in ways
  that look like real bugs.
- Enrich the model to make it "more realistic." Importing proto
  schemas, Malli contracts, or production component interfaces into
  `test-model` defeats the property test.

## Discussion

The two-test-forms split keeps fast tests fast and slow tests
predictable. `deftest` for pure functions is cheap, parallel, and
doesn't need infrastructure. The scenario runner is deliberately heavier
— it boots real components — and is the only place where system-level
guarantees are established.

The scope rule for a brick's tests is what keeps the split honest. A
test that drives another write brick to put a party or a product on
record has quietly become a scenario, and it now depends on that brick
being in every project that hosts the brick under test, which polylith
does not check for test code. The symptom is a namespace that loads in
the development project and fails in a service project; the fix is to
move the case, not to widen the project.

The scenario "don'ts" are about preserving the property test's ability
to find bugs. If the model imports proto, Malli, or production
interfaces, the model and reality converge on the same wrongness and
the property stops finding bugs. If projections leak into production
code, the test concern leaks into production. Both rules look pedantic;
both pay off when a real bug surfaces and the property finds it.

## References

- [ADR-0009](../../adr/0009-model-equality-property-testing.md) —
  Model-equality property testing
- [docs/tdd/scenario-testing.md](../../tdd/scenario-testing.md)
- [test-system](test-system.md) — `with-test-system`, `nom-test>`, and
  the runner's bounds, mono's recipe
- [service-configurations](../code/service-configurations.md) — what
  `test-startup` checks
- [error-handling.md](../code/error-handling.md)
