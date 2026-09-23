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
drives the command pipeline is a scenario — `test-scenarios` drives the
domain and owns the model-equality property test, `test-api-scenarios`
drives the HTTP surface through real `api` requests — and the HTTP
contract is an EDN scenario there, never a brick `interface_test.clj`.
Beyond test infrastructure (`fdb`, `testcontainers`, `schema`,
`changelog-relay`, the `test-*` bricks) and `*-query` bricks, a brick's
tests require only what its own `src` requires: a fixture that needs
another write brick — a party, a product version, a policy on record —
makes the case a scenario, and a service project's `:test` alias is
never widened so the namespace loads; the rare exception carries
`;; enforce-idioms: brick-test-scope -- <reason>` on the line above the
require. Run `just test` as the default — the bricks changed since the
last `stable-*` tag, in the development project — and `just test-all`
as the full suite, which adds every service project's start-up check
and the migrator's guard whatever changed; one brick is
`project:dev brick:<name> :all`. Both recipes cap the JVM and set
`TEST_SYSTEM_PERMITS` to Docker's CPU count. The model is pure functions
over a Clojure map: it talks to no FDB, message bus or other real
infrastructure and imports nothing from production; projections live in
`test-projections`, each assertion projects narrowly, and the runner's
quiescence wait is never skipped.
Commands: `just test`, `just test-all`.
See [testing](../../../docs/recipes/test/testing.md).
