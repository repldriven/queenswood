# Queenswood Clojure idioms

Write new Clojure to these conventions — Queenswood's own hygiene on
top of mono's `idioms` rule, stated as *how to write*, not what to
avoid. Deterministic linters (semgrep and the guardrails in the
pre-commit hook) catch regressions; these rules keep you from
introducing them.

## A test is a `deftest`, or a scenario

A brick's `deftest`s cover its pure functions and its own store and
changelog against FDB under `with-test-system`, reading back through
the brick's query sibling. Anything that crosses a brick boundary or
drives the command pipeline is a scenario, never a `deftest`-style
integration test, and the HTTP contract is an EDN scenario in
`test-api-scenarios`, never a brick `interface_test.clj`. A brick's
tests never require another write brick's interface to build a
fixture — a test that needs a party, a product version or a policy on
record is a scenario. In scope without a marker: `fdb`,
`testcontainers`, `schema`, `changelog-relay`, the `test-*` bricks, any
`*-query` brick, and what the brick's own `src` requires; anything else
carries `;; enforce-idioms: brick-test-scope -- <reason>` on the line
above the require. Never add a component to a service project's
`:test` alias so a brick's test namespace loads; narrow the test
instead. Run `just test` as the default, the changed bricks in the
development project, and `just test-all` as the full suite, which adds
the start-up check of every service project and the migrator's guard
whatever changed; run one brick with `project:dev brick:<name> :all`,
and cap the JVM's processor count and set `TEST_SYSTEM_PERMITS` to
Docker's CPU count on a raw `clojure -M:poly test`, as both recipes do.
The model is pure functions over a Clojure map: it talks to no FDB,
message bus or other real infrastructure, and imports no proto
schemas, Malli contracts or production component interfaces.
Projections live in `test-projections`, never in production
components, and each assertion projects narrowly; the runner's
quiescence wait is never skipped.
Commands: `just test`, `just test-all`.
See [testing](../../../docs/recipes/test/testing.md).
