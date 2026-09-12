# Queenswood Clojure idioms

Write new Clojure to these conventions — the load-bearing "Critical
guardrails" from CLAUDE.md, stated as *how to write*, not what to avoid.
Deterministic linters (semgrep in the pre-commit hook) catch
regressions; these rules keep you from introducing them.

## Return anomalies, don't throw across a boundary

A component `interface.clj` returns a value or an anomaly — it never
raises. Produce a domain rejection with `error/reject`; convert an
exception at a library edge with `error/try-nom` / `error/try-nom-ex`.
Thread fallible steps with `let-nom>` / `nom->`. Name a category for
the call site (`:http-client/request`) when nobody outside the process
can act on the failure, and for the problem when someone can — every
rejection (`:bank/invalid-status`), plus the storage failures that mean
retry (`:fdb/contention`, `:fdb/timeout`), with the call site moving to
the payload as `:operation`. A genuinely unrecoverable `throw` is rare
and carries `;; nosemgrep: no-raw-throw` on the line above.
See [error-handling](../../../docs/recipes/code/error-handling.md),
[ADR-0005](../../../docs/adr/0005-error-handling-with-anomalies.md).

## IDs and timestamps come from `utility`

`util/uuidv7` for IDs, `util/now` for timestamps. Never `random-uuid`,
`UUID/randomUUID`, `Instant/now`, or `System/currentTimeMillis` outside
`components/utility/` — that brick is the only place those primitives are
called. For any non-`clojure.core` helper, check `utility` first.
See [common-helpers](../../../docs/recipes/code/common-helpers.md),
[code-style](../../../docs/recipes/code/code-style.md).

## Tests drive the system with `with-test-system`

A brick's `deftest`s cover its pure functions and its own store and
changelog against FDB. Anything that crosses a brick boundary or
drives the command pipeline is a scenario, and the HTTP contract is
an EDN scenario in `test-api-scenarios`, never a brick
`interface_test.clj`. Beyond test infrastructure and `*-query`
bricks, a brick's tests require only what its own `src` requires: a
fixture that needs another write brick — a party, a product version,
a policy on record — makes the case a scenario, and a service
project's `:test` alias is never widened so the namespace loads; the
rare exception carries `;; enforce-idioms: brick-test-scope -- <reason>`
on the line above the require. Run `just test` as the default, and
one brick with `project:dev brick:<name> :all`; a raw
`clojure -M:poly test` needs `TEST_SYSTEM_PERMITS` and the processor
cap set as `just test` sets them. Manage system lifecycle with
`with-test-system`, which holds a permit while its system is up;
mark a namespace whose tests share state, such as a `with-redefs`,
`^:eftest/synchronized` so its vars run one at a time, and never one
that only boots infrastructure; keep per-brick config at
`test-resources/<brick>/application-test.yml`, and assert
anomaly-freeness with `nom-test>`. Never `use-fixtures`.
See [testing](../../../docs/recipes/test/testing.md).

## Comment the why, not the what

`interface.clj` docstrings are the documentation surface: a
one-paragraph ns docstring, and each public fn's contract on the
re-export. Impl defs, private fns and non-interface files stay bare.
Commentary attaches to the name it describes — `^{:doc ...}` metadata
on a `def`, the docstring position on a `defn` — never a `;;` block
floating above the form. Say what the thing does and what its
conditionals do, not why it is shaped that way and not what category
it belongs to. A comment explaining a literal means the literal wants
a name: extract a documented constant instead. An inline `;;` survives
only when it guards a specific edit a reader would otherwise get
wrong; when trimming, delete restatement, control-flow narration and
references to the current change, promoting a load-bearing why to the
docstring. `;; ---` separators belong in `components.clj` and
`interface.clj` only.
See [ADR-0015](../../../docs/adr/0015-comments-and-docstrings.md).

## Requires run innermost to outermost

Order `:require` in nine groups, blank line between each, alphabetical
within: this brick's own `system` namespace; Queenswood extension
namespaces; `mono` extension namespaces; this file's own package; the
rest of the brick; other Queenswood interfaces; `mono` interfaces;
external libraries; `clojure.*`. In a flat component the brick is the
package, so the two internal groups collapse into one. A bare require
— no `:as`, no `:refer` — takes the bracketed form
`[com.example.ns]`, never unbracketed. In a component interface test
the SUT takes the own-package slot, aliased `SUT`, and nothing else
from that component is required.
See [code-style](../../../docs/recipes/code/code-style.md).

## Everyday shape

kebab-case keyword keys throughout (string ISO-4217 currency the one
deliberate exception); `cond->` with `utility/assoc-some` /
`assoc-seq` over chains of optional `assoc`; destructure one map level
per `let` binding.
See [code-style](../../../docs/recipes/code/code-style.md),
[ADR-0006](../../../docs/adr/0006-kebab-case-keyword-keys.md).
