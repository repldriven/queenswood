# Code generation

<!-- tessl-plugin: design -->

## Problem

You want to generate code from a schema or other source
artefact for use in a brick — for example, protobuf record
definitions for FDB Record Layer use.

## Solution

We use Clojure's standard `:deps/prep-lib` mechanism with a
co-located `build.clj`. Generated code lives in a brick-local
`gen/` folder, ignored by git. The actual generation logic
lives in `bases/build`; each brick's `build.clj` is a thin
shim that delegates to it.

### File layout

```
components/<brick>/
  src/...                ; hand-written source
  gen/                   ; generated source — on classpath, gitignored
    .gitignore           ; * and !.gitignore
  classes/               ; compiled artefacts (when applicable)
  build.clj              ; thin shim, delegates to bases/build
  deps.edn               ; declares :deps/prep-lib + :build alias
```

The `gen/.gitignore` keeps the directory tracked but excludes
its contents:

```
*
!.gitignore
```

### deps.edn

The brick's `deps.edn` declares `:deps/prep-lib` and a
`:build` alias that brings in `tools.build` and the shared
`bases/build`. Adapted from `components/schema/deps.edn`:

```clojure
{:paths ["src" "gen" "resources" "classes"]
 :deps {pin/protojure {:local/root "../../deps/protojure"}
        pin/fdb {:local/root "../../deps/fdb"}}
 :deps/prep-lib {:alias :build
                 :fn build/gen-proto
                 :ensure "classes"}
 :aliases
 {:build
  {:deps {io.github.clojure/tools.build
          {:mvn/version "0.10.12"}
          pin/fdb {:local/root "../../deps/fdb"}}
   :extra-deps {com.repldriven.queenswood/build
                {:local/root "../../bases/build"}}
   :paths ["."]}}}
```

The protobuf and record-layer coordinates come from the `deps/`
shims rather than being written here — see
[library-pins.md](library-pins.md) for why the versions
live in one place and which of them need an `:exclusions` entry
to hold. `pin/fdb` appears twice because an alias's `:deps` key
replaces the project's rather than extending it: `b/javac` builds
its basis from the brick's `deps.edn` with no aliases, while
`gen-proto` resolves the record-layer JAR off the `:build`
alias's classpath to extract its `.proto` files.

Key fields:

- `:paths` includes `"gen"` so generated source is on the
  classpath.
- `:deps/prep-lib :alias` names the alias to activate when
  running prep.
- `:deps/prep-lib :fn` names the function in `build.clj` to
  call.
- `:deps/prep-lib :ensure` is the path Clojure checks to
  decide whether prep is up-to-date. When this path doesn't
  exist, prep runs.

### build.clj

A thin shim — the real logic lives in `bases/build`:

```clojure
(ns build
  (:require
    [com.repldriven.queenswood.build.proto :as proto]))

(defn gen-proto [opts] (proto/gen-proto opts))
```

If a new brick needs generation that doesn't yet have a build
implementation in `bases/build`, add the shared logic there
first, then call it from the brick's `build.clj` shim.

### Running prep

Default — prep all libraries that need preparation:

```bash
clj -X:deps prep :aliases '[:dev]'
```

Every brick, including the proto bricks under `schema`, is
on the `:dev` alias in the top-level `deps.edn`, so `:dev` covers
all generation.

### When to force

`:deps/prep-lib :ensure` makes prep idempotent — it skips
bricks whose `:ensure` path already exists. After a
source-schema change (editing a `.proto` file, for example),
the existing `classes/` folder doesn't reflect the new
schema but Clojure thinks prep is done. Use `:force true` to
re-run regardless:

```bash
clj -X:deps prep :aliases '[:dev]' :force true
```

This is the most common foot-gun. If a freshly regenerated
artefact still looks like the old version, you forgot
`:force`.

## Rules

**MUST:**

- Use Clojure's standard `:deps/prep-lib` mechanism for code
  generation.
- Co-locate a `build.clj` in the brick that needs generation;
  delegate the work to `bases/build`.
- Place generated code in a `gen/` folder inside the brick.
- Add a `gen/.gitignore` that ignores everything except
  itself (`*` and `!.gitignore`).

**MUST NOT:**

- Commit generated code.
- Use external build systems (Maven plugins, Gradle,
  Babashka tasks) for generation when prep-lib is the
  standard mechanism.
- Skip `:force true` after a source-schema change — the
  prep marker is stale.

**SHOULD:**

- Add new generation logic to `bases/build` (so other bricks
  can reuse it) before customising it in a brick's
  `build.clj`.

## Discussion

`:deps/prep-lib` is the standard Clojure tooling for code
generation. Using it means new contributors recognise the
shape immediately, no extra build vocabulary to learn, and
Renovate upgrades of `tools.build` flow through normally.

The thin `build.clj` shim plus shared `bases/build`
implementation keeps generation logic in one place. If we
need to fix a bug in proto generation, we fix it in
`bases/build` once and every brick picks it up. The shim in
each brick is small enough that it can be rewritten by hand
if needed.

The gitignored `gen/` convention keeps git diffs clean. A
change to a `.proto` file should show as a one-file diff,
not a thousand lines of regenerated Java. Reviewers see
intent, not derived output.

The `:force true` foot-gun is real and worth flagging. The
`:ensure` check is conservative — it doesn't compare
timestamps, just checks for the marker's presence. After any
source-schema change, force.

## References

- [ADR-0010](../../adr/0010-code-generation-via-prep-lib.md) —
  Code generation via :deps/prep-lib
- `bases/build` (shared generation logic)
