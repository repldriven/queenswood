# Testing

<!-- tessl-plugin: idioms -->

## Problem

You want to write a test for some part of Queenswood.

## Solution

We use three tiers of test, each with one job:

- **A brick test**, a `clojure.test/deftest`, for a brick's own logic:
  pure functions exercised by passing values and asserting on the
  return, and the brick's own store and changelog against a real FDB
  under `with-test-system`.
- **A domain scenario**, in `test-scenarios`, for the bank's rules over
  sequences of commands, driven through component interfaces. Fugato
  property tests and hand-authored EDN scenarios share its runner, which
  owns the model-equality property test. An EDN scenario is compared with
  the model after every step, or marked `:model :reality` when it names
  a verb the model has no rule for.
- **An API scenario**, in `test-api-scenarios`, for the HTTP contract and
  the PRDs' user journeys, driven through real `api` requests.

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

### Choosing a tier

A case goes to the first tier whose question it answers yes:

1. Is it a pure function, or the brick's own store or changelog? A brick
   test, reading back through the brick's query sibling.
2. Is it what a client sees: a status, a problem body, a header, a link,
   an auth boundary, or a step of a PRD journey? An API scenario.
3. Is it a rule over a sequence the model can express? A domain
   scenario compared against the model.
4. Can a client reach it through the public API, including the provider
   simulators the rig boots? An API scenario.
5. Otherwise, a reality-only domain scenario.

A case lives in one tier. Needing a party, a product version, a policy
or a balance on record first puts a case in a scenario, however small:
driving another write brick to build a fixture is the boundary. See
[ADR-0009](../../adr/0009-model-equality-property-testing.md) and
[docs/tdd/scenario-testing.md](../../tdd/scenario-testing.md) for the
architecture.

### What a brick's tests may do

A brick test never sends a command or an event, never subscribes to a
channel, and never writes a record through another write brick's
interface. The semgrep rule `brick-test-drives-pipeline` fails on
`processor/process`, `commands/dispatch`, `message-bus/send`,
`message-bus/subscribe` and `event/publish` in a brick's or a base's test
tree, the `test-*` bricks and `changelog-relay` excepted. An adapter's
test calling its own command processor, and a brick's test handing its
own event handler an envelope with no bus, carry the reason on a comment
line and `;; nosemgrep: brick-test-drives-pipeline` on the line below
it, directly above the call.

A brick's test tree may require test infrastructure (`fdb`,
`testcontainers`, `schema`, `changelog-relay`, the `test-*` bricks),
any `*-query` brick, and the bricks the brick's own `src` already
requires. Anything else means the test has grown into a scenario: move
the case rather than adding the component to a service project's
`:test` alias so the namespace loads.

The pre-commit hook (`scripts/hooks/enforce-idioms.sh`, check
`brick-test-scope`) fails on such a require, in a base's tests as in a
component's. The rare sanctioned
exception carries `;; enforce-idioms: brick-test-scope -- <reason>` on
the line above the require, with the reason on that one line.
Polylith's `poly check` validates `src` requires only, which is why the
breach otherwise surfaces in CI, when the service project that lacks
the component runs its tests.

## Rules

**MUST:**

- Use `clojure.test/deftest` for pure functions, and for a brick's own
  store and changelog against FDB under `with-test-system`, reading back
  through the brick's query sibling.
- Put a case in the first tier whose question it answers yes: a brick
  test for a pure function or the brick's own store or changelog; an API
  scenario for what a client sees or a PRD journey step; a compared
  domain scenario for a rule the model can express; an API scenario for
  anything else a client can reach; otherwise a reality-only domain
  scenario.
- Use a scenario for anything that crosses a brick boundary or drives
  the command pipeline.
- Mark a domain scenario `:model :reality` when it names a `:reality`
  verb; every other one is compared with the model after every step.
- Put a scenario's state-changing steps in `:given` and `:when`, and only
  reads and assertions in `:then`.
- Pin the HTTP contract as an EDN scenario in `test-api-scenarios`,
  never in a brick's `interface_test.clj`.
- Write a PRD's user journey as a scenario under `journeys/<prd>/`, one
  file per journey named for its heading, running on every provider it
  touches, and delete in the same change a tag scenario that asserted
  only that journey's path.
- Run `just test` as the default, the changed bricks in the development
  project, and `just test-all` as the full suite: the default plus the
  start-up check of every service project and the migrator's guard,
  whatever changed.
- Run one brick with `project:dev brick:<name> :all`.
- Cap the JVM's processor count and set `TEST_SYSTEM_PERMITS` to
  Docker's CPU count on a raw `clojure -M:poly test`, as `just test`
  and `just test-all` do.

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
- Write `deftest`-style integration tests against the command pipeline:
  a brick test never sends a command or an event, subscribes to a
  channel, or writes through another write brick's interface. An
  adapter's own command processor and a brick's own event handler handed
  an envelope MAY stay, the reason on the comment line above
  `;; nosemgrep: brick-test-drives-pipeline`. Check the tree with
  `just semgrep`.
- Keep a case in two tiers. Where a domain scenario and an API scenario
  assert the same thing, the API scenario keeps it unless the domain one
  is compared.
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

The tiers keep fast tests fast and slow tests predictable. A brick test
of a pure function is cheap, parallel, and needs no infrastructure. A
scenario is heavier — it boots real components — and is the only place
where system-level guarantees are established.

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
