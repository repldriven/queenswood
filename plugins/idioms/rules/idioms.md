# Queenswood Clojure idioms

Write new Clojure to these conventions — Queenswood's own hygiene on
top of mono's `idioms` rule, stated as *how to write*, not what to
avoid. Deterministic linters (semgrep and the guardrails in the
pre-commit hook) catch regressions; these rules keep you from
introducing them.

## A test is a `deftest`, or a scenario

Put a case in the first tier whose question it answers yes: a brick
`deftest` for a pure function or the brick's own store and changelog
against FDB under `with-test-system`, read back through the brick's
query sibling; an API scenario in `test-api-scenarios` for what a client
sees — the HTTP contract, never a brick `interface_test.clj` — or a PRD
journey step; a domain scenario compared against the model for a rule it
can express; an API scenario for anything else a client can reach; and
otherwise a reality-only domain scenario. A case lives in one tier: where
a domain and an API scenario assert the same thing, the API scenario
keeps it unless the domain one is compared. Anything that crosses a
brick boundary or drives the command pipeline is a scenario. A brick
test never sends a command or an event, subscribes to a channel, or
writes through another write brick's interface — a test that needs a
party, a product version or a policy on record is a scenario; an
adapter's own command processor and a brick's own event handler handed
an envelope MAY stay, the reason on the comment line above
`;; nosemgrep: brick-test-drives-pipeline`. In scope for a brick's tests
without a marker: `fdb`, `testcontainers`, `schema`, `changelog-relay`,
the `test-*` bricks, any `*-query` brick, and what the brick's own `src`
requires; anything else carries
`;; enforce-idioms: brick-test-scope -- <reason>` on the line above the
require. Never add a component to a service project's `:test` alias so
a brick's test namespace loads; narrow the test instead. Run `just test`
as the default, the changed bricks in the development project, and
`just test-all` as the full suite, which adds the start-up check of
every service project and the migrator's guard whatever changed; run one
brick with `project:dev brick:<name> :all`, and cap the JVM's processor
count and set `TEST_SYSTEM_PERMITS` to Docker's CPU count on a raw
`clojure -M:poly test`, as both recipes do. The model is pure functions
over a Clojure map: it talks to no FDB, message bus or other real
infrastructure, and imports no proto schemas, Malli contracts or
production component interfaces. Projections live in
`test-projections`, never in production components, and each assertion
projects narrowly; the runner's quiescence wait is never skipped.
Commands: `just test`, `just test-all`, `just semgrep`.
See [testing](../../../docs/recipes/test/testing.md).
