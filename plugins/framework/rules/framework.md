# Queenswood Polylith framework

How Queenswood uses Polylith beyond mono's `framework` rule — the
aggregator bases that compose several bases into one process, and the
library versions pinned under `deps/`.

## Aggregator bases are the one base-on-base exception

Bases never depend on other bases, except a designated multi-base
aggregator — `monolith` (the whole bank, for local dev and end-to-end
tests) and `external-adapters` (every vendor adapter and its simulator)
— on the bases it composes. A base that only ever appears inside an
aggregator is a composed base: it has no project and no `-main`, and
carries an `interface.clj` that bare-requires its own `system`
namespace and exposes what the aggregator wires in (typically `app`).
An aggregator reaches a composed base by that interface and nothing
else — `.api` is reserved for a base that has none. `poly` still treats
it as a base, so `enforce-idioms.sh` enforces this, not Polylith. A
base never owns a store: it may bare-require `fdb.interface` from its
`system.clj` to register FDB component-kinds and nothing more, and
`store-in-a-base` in `enforce-idioms.sh` blocks the rest.
See [aggregator-bases](../../../docs/recipes/code/aggregator-bases.md).

## Library versions are pinned once, under `deps/`

Pin a library several bricks or projects share in one shim under
`deps/`, referenced as `pin/<name>` — `pin/protojure`, `pin/fdb`,
`pin/clojure-core-async` — never declared in a brick or project
directly. Pinning down needs the competing copy excluded where it
enters, which is what `pin/protojure` and `pin/fdb` do. Every project
repeats `org.clojure/clojure`, and `just check-versions` asserts the
copies against the root `deps.edn`. Renovate owns the bumps.
Commands: `just check-versions`.
See [library-pins](../../../docs/recipes/code/library-pins.md).
