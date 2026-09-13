# Aggregator bases

<!-- tessl-plugin: framework -->

## Problem

You want to run several bases in one process — the whole bank for local
development and end-to-end tests, or every vendor adapter with its
simulator — or you are adding a base that only ever runs inside one.

## Solution

Bases never depend on other bases, with one bounded exception: a
designated multi-base aggregator. Two exist: `monolith`, the whole bank
in one in-process system for local dev and the Testcontainers-backed
end-to-end tests, and `external-adapters`, every vendor adapter and its
simulator. An aggregator requires each composed base's `interface.clj`,
which extends that base's multimethods on load and hands back the
handler to wire into the aggregator's own system definition.

### A composed base

A *composed* base — one bundled into an aggregator rather than deployed
on its own — has no project, so it has no `-main` of its own, and it
carries an `interface.clj` like a component's, so the aggregator reaches
it by the same rule as everything else:

```clojure
(ns com.repldriven.queenswood.clearbank-simulator.interface
  "One-paragraph summary."
  (:require
    [com.repldriven.queenswood.clearbank-simulator.system]

    [com.repldriven.queenswood.clearbank-simulator.api :as api]))

(defn app
  "Ring handler for the ClearBank simulator's HTTP surface. ..."
  [ctx]
  (api/app ctx))
```

Requiring it registers the base's system component-kinds (via the bare
`.system` require) and exposes its handler, so an aggregator needs one
require per composed base instead of reaching into `.api` and `.system`
separately. `poly` does not recognise it as an interface — the brick
stays a base and gets no interface-mismatch checking — so this is a
convention `scripts/hooks/enforce-idioms.sh` enforces, not a Polylith
feature. `.api` is reserved for a base that has no interface.

### What a base does not own

A base owns no store. `component → base` is disallowed, so state parked
behind an entry point is unreachable by any component, and the
`store-in-a-base` check in `enforce-idioms.sh` blocks it. A base still
registers FDB component-kinds by bare-requiring `fdb.interface` from its
`system.clj`; that is registration, not access.

## Rules

**MUST:**

- A composed base has an `interface.clj` that bare-requires its own
  `system` namespace and exposes whatever the aggregator wires in
  (typically `app`), and has no project and no `-main`.
- An aggregator reach a composed base by its `interface.clj`, and by
  nothing else.

**MUST NOT:**

- A base depend on another base, except a designated aggregator
  (`monolith`, `external-adapters`) on the bases it composes.
- An aggregator reach a composed base by `.api` or `.system`. `.api` is
  reserved for a base that has no interface.
- A base own a store. Persistence belongs in a component; a base may
  bare-require `fdb.interface` from its `system.clj` to register FDB
  component-kinds, and nothing more. Enforced by `store-in-a-base` in
  `scripts/hooks/enforce-idioms.sh`.

## Discussion

The exception is scoped to the aggregators — it doesn't license
base-to-base dependency anywhere else. Giving a composed base an
interface is what keeps the exception narrow: the dependency is still
base-to-base, but it crosses at a declared surface rather than reaching
into another base's internals. The composed bases are the ClearBank,
Onfido and Companies House adapters and simulators. They stay bases for
the same reason `api` is one: each carries a large surface — routes,
handlers, examples, wire schemas — rather than the require bundle a thin
base amounts to. Moving that surface into components is the standing
alternative, not a pending step.

A base owning a store is a one-way door, which is why it is blocked
rather than discouraged. The day a component needs the state, the store
has to move first. It would also drop out of the guarded set without
saying so: `check-processors` iterates `components/*`, and semgrep's
`fdb-outside-store` is pathed to `/components/*/src/**`, so neither
would see a store that had moved into `bases/`.

## References

- [bases](bases.md) — what every base owns, mono's recipe
- [deployment](../infra/deployment.md) — the per-service split the
  production deployables follow
- [ADR-0019](../../adr/0019-processor-packaging.md) — one thin base per
  service group
- [git-hooks](../practices/git-hooks.md) — the guardrails hook
