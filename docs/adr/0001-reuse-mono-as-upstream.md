# 1. Consume `mono` as a pinned dependency for shared infrastructure

<!-- tessl-plugin: design -->

## Status

Accepted. Queenswood was originally a *domain fork* of `mono`; it now
consumes mono as a versioned git-dependency. This document describes the
current arrangement, not the fork it replaced.

## Context

Queenswood is a banking system. Like any backend of meaningful size it
needs a long list of infrastructure: persistence, message bus, HTTP
server, environment loading, error handling, logging, telemetry,
testcontainers, system lifecycle, code generation, JSON, encryption,
vault integration. None of it is bank-specific.

That infrastructure lives in a separate, domain-independent Polylith
workspace with its own tests:
[mono](https://github.com/repldriven/mono). The question is how to
reuse it. Everything else about Queenswood's organisation — workspace
structure, dependency management, deployment shape — flows from the
answer.

The options:

- **Re-implement infrastructure inside Queenswood.** Duplication of code
  that already exists; ongoing divergence as mono evolves; no upside.
- **Domain fork.** Pull mono's bricks directly into the workspace and add
  domain bricks on top, tracking upstream with `git merge`. This was the
  original arrangement. It made every infra brick locally editable, but
  there was no reproducible *version* of mono — the code present was
  whatever was last merged — local edits to a mono brick conflicted with
  upstream merges, and the workspace carried the full weight of mono's
  bricks and tests.
- **Consume mono as a pinned git-dependency.** Depend on mono at a
  specific tag/sha via `tools.deps`; keep only Queenswood's own domain
  bricks in the workspace; take shared infra from the dependency's
  classpath.

## Decision

We consume mono as a **pinned git-dependency**, not a fork.

The workspace holds only Queenswood's domain bricks. Shared infrastructure
comes from `com.repldriven/mono`, pinned to a specific tag and sha. Two
shim directories under `deps/` carry that coordinate: `deps/mono` (runtime,
rooted at mono's `projects/mono-lib`) and `deps/mono-test` (the test
superset, rooted at `projects/mono-test-lib`, which adds `test-system` and
`testcontainers`). Every project references them under one symbol,
`ext/mono`; a project's `:test` alias re-points `ext/mono` at the test
superset, so the root is swapped by symbol. Upgrading mono is a one-line
tag/sha bump in those two shims.

Because domain bricks no longer share a workspace with mono's infra, they
carry no distinguishing prefix — a component is just a component. External
infra namespaces stay `com.repldriven.mono.*`; Queenswood's own bricks are
`com.repldriven.queenswood.*`.

This decision implies Polylith as the workspace structure, since mono is a
Polylith workspace and `mono-lib` is a Polylith aggregate. Polylith brings
clear interface boundaries, brick-level test scoping, and
projects-as-deployment-targets. We do not re-argue Polylith here; see
[the Polylith documentation](https://polylith.gitbook.io/polylith).

## Consequences

Easier:

- **Reproducible.** The mono version is pinned by tag and sha, not
  "whatever was last merged". Upgrades are deliberate and reviewable — one
  commit bumping the two shims.
- **No merge cost.** There is no upstream branch to reconcile; a mono
  release is just a new pinned version.
- **Smaller workspace.** Only domain bricks live here, with their own
  tests; mono's bricks and tests are the dependency's concern.
- **Clean separation.** `com.repldriven.mono.*` on the classpath is
  external infra; `com.repldriven.queenswood.*` is ours. The boundary is
  the namespace root, enforced by where the code physically lives.

Harder:

- **Changing infra is a round-trip.** A flaw in Queenswood that traces to
  a mono brick is fixed upstream in mono, released as a new tag, then
  pulled down via a shim bump — where the fork let you edit the brick in
  place. This is the deliberate trade for reusability: reusing mono's
  infrastructure rather than reimplementing or owning a copy of it.
- **Two repos to navigate.** Infra source lives in the mono checkout under
  `~/.gitlibs`, not the workspace. CLAUDE.md and this ADR set should keep
  that navigable.
