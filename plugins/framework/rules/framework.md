# Queenswood Polylith framework

How Queenswood uses Polylith beyond mono's `framework` rule — the
aggregator bases that compose several bases into one process, and the
library versions pinned under `deps/`.

## Aggregator bases are the one base-on-base exception

Bases never depend on other bases, except a designated aggregator —
`monolith`, `external-adapters` or `external-simulators` — on the bases
it composes. A composed base has no project and no `-main`, and carries
an `interface.clj` that bare-requires its own `system` namespace and
exposes whatever the aggregator wires in (typically `app`). An
aggregator reaches a composed base by that interface and nothing else,
never by `.api` or `.system`; `.api` is reserved for a base that has no
interface. A base never owns a store — persistence belongs in a
component: it may bare-require `fdb.interface` from its `system.clj` to
register FDB component-kinds and nothing more, and `store-in-a-base` in
`scripts/hooks/enforce-idioms.sh` enforces it.
See [aggregator-bases](../../../docs/recipes/code/aggregator-bases.md).

## Library versions are pinned once, under `deps/`

Pin a library several bricks or projects share in one shim under
`deps/`, referenced as `pin/<name>`, and never declare a shimmed
library's version in a brick or project directly. When pinning a
library down, exclude the competing copy where it enters: a shim one
level below a direct dependency loses. Repeat `org.clojure/clojure` in
every project and keep the copies equal. Never bump a version Renovate
manages by hand.
Commands: `just check-versions`.
See [library-pins](../../../docs/recipes/code/library-pins.md).
