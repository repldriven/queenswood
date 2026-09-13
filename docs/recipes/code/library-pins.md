# Library pins

<!-- tessl-plugin: framework -->

## Problem

You need to hold a library at one version across bricks and projects,
or a transitive copy of it is winning over the one you declared.

## Solution

A library that several bricks share, or that must be held at a
particular version for binary compatibility, is pinned once in a shim
under `deps/` and pulled in by `:local/root` — the same pattern the
`ext/mono` shims use for the upstream coordinate. Consumers reference
the shim under a `pin/` key:

- `pin/protojure` — protojure plus `protobuf-java` 3.x. protojure ships
  4.x, which the FDB Record Layer cannot load, so the shim excludes
  protojure's copy outright.
- `pin/fdb` — `fdb-java` plus `fdb-record-layer-core`. The record layer
  ships an older `fdb-java`, which the shim excludes.
- `pin/clojure-core-async` — `core.async`.

Pinning *up* works unaided, because the resolver's tie-break at equal
depth takes the newer version. Pinning *down* does not: the shim's copy
sits one level below a direct dependency and loses, so the competing
copy has to be excluded at the point it enters — which is what
`pin/protojure` and `pin/fdb` do.

`org.clojure/clojure` cannot be shimmed at all, since the CLI makes it a
direct dependency of every project; each project repeats the pin, and
`just check-versions` asserts the copies against the root `deps.edn`.

## Rules

**MUST:**

- Pin a library several bricks or projects share in one shim under
  `deps/`, referenced as `pin/<name>`.
- Exclude the competing copy where it enters when pinning a library
  *down*; a shim one level below a direct dependency loses.
- Repeat `org.clojure/clojure` in every project, and keep the copies
  equal — `just check-versions`.

**MUST NOT:**

- Declare a shimmed library's version in a brick or project directly.
- Manually bump a version Renovate manages; see
  [git-workflow](../practices/git-workflow.md).

## References

- [projects](projects.md) — why pinning up and down differ, mono's
  recipe
- [code-generation](code-generation.md) — the `pin/protojure` and
  `pin/fdb` shims in a brick's `:build` alias
- [git-workflow](../practices/git-workflow.md) — Renovate owns the
  bumps
