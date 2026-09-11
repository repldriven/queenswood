# Common helpers

<!-- tessl-plugin: idioms -->

## Problem

You need a helper function (string, map, date, ID, etc.) that
isn't in `clojure.core`.

## Solution

The `utility` brick is the project's canonical home for three
kinds of helper:

- **Small wrappers** — concepts too small to warrant their own
  component, where we still want a single canonical surface
  (UUID generation, timestamp reads, string ↔ stream
  conversion).
- **Standard-library-like idioms** — helpers that feel like
  they *should* exist in `clojure.core` but don't, such as
  `deep-merge` or map-key/value manipulation.
- **Convergence forcing** — anything we want a single name and
  one implementation for, used the same way everywhere.

Before reaching for an ad-hoc local function or copying
something from a blog post, work through this sequence:

1. **Is it in `clojure.core` or a standard namespace
   (`clojure.string`, `clojure.set`, `clojure.walk`, etc.)?**
   Use it directly with its qualified name.
2. **Is it already exposed by `utility`?** Use it from there.
3. **Does an established Clojure helper library already
   provide it?** A good example is
   [weavejester/medley](https://github.com/weavejester/medley)
   — small, generic, well-maintained — but other libraries
   exist. If a helper there fits the need, add the library to
   `components/utility/deps.edn` and re-export through
   `utility.interface`. Don't pull the library directly into
   another brick — see
   [ADR-0011](../../adr/0011-one-component-per-third-party-library.md).
4. **Is it nowhere?** Implement inside the `utility` brick in
   the appropriate sub-namespace and re-export.

The point is convergence: helpers live in one place, with one
name, used the same way everywhere in the codebase.

### What `utility` already exposes

Inspect
`components/utility/src/com/repldriven/mono/utility/interface.clj`
for the current canonical list. As a snapshot:

- **Collections**: `deep-merge`, `record->map`, `keys->strs`,
  `val-strs->keywords`, `yaml-collections->edn-collections`.
- **Strings**: `str->bytes`, `string->stream`,
  `resolve-source`, `prop-seq->kw-map`.
- **UUIDs**: `uuidv7`.
- **IDs**: `generate-id`.
- **Time**: `now`, `now-rfc3339`.
- **Vars**: `vname`.

### Adding a new helper

`utility` is internally organised by sub-namespace
(`utility.collections`, `utility.string`, `utility.time`, and
so on). Add the implementation to the right sub-namespace and
re-export from `utility.interface`.

If the function exists in a helper library (medley or
similar), the sub-namespace just re-exports it:

```clojure
;; utility/collections.clj
(:require [medley.core :as medley])
(def deep-merge medley/deep-merge)

;; utility/interface.clj
(:require
  [com.repldriven.mono.utility.collections :as util.collections])
(def deep-merge util.collections/deep-merge)
```

Otherwise, implement it directly:

```clojure
;; utility/string.clj
(defn slugify
  [s]
  ...)

;; utility/interface.clj
(:require
  [com.repldriven.mono.utility.string :as util.string])
(def slugify util.string/slugify)
```

Either way, callers see a single `util/<name>` API and don't
care whether the implementation is local or wrapping a library.

## Rules

**MUST:**

- Check `clojure.core` and the standard namespaces first, then
  `utility`, then helper libraries (medley or similar), before
  writing a new helper.
- Add new helpers to `utility` — implement in the appropriate
  sub-namespace and re-export from `utility.interface`.
- Re-export from a helper library where the function already
  exists there, instead of reimplementing.

**MUST NOT:**

- Reimplement a helper that's already in `utility` or
  `clojure.core`.
- Add ad-hoc helper functions in arbitrary bricks when the
  helper is general (not domain-specific).
- Pull a third-party helper library directly into another
  brick. Wrap it through `utility` per
  [ADR-0011](../../adr/0011-one-component-per-third-party-library.md).

**SHOULD:**

- Group helpers in `utility` by domain
  (`utility.collections`, `utility.string`, etc.) for
  navigability.
- Hoist a helper into `utility` once a second brick would
  plausibly want it; don't wait for the third.

## Discussion

Convergence is the load-bearing principle here. Once a helper
exists in two places, it will exist in three; once it exists
in three, bug fixes never land consistently. Forcing the
canonical location to be `utility` means:

- Reviewers know where to look. "Where's the slugify
  function?" has a single answer.
- Bug fixes land once. Fixing `deep-merge`'s edge case in
  `utility` fixes it for every caller.
- ADR-0011's one-component-per-library principle is honoured
  for helper libraries — `utility` is the one consumer; other
  bricks consume helpers through `utility`, not the library
  directly.

When in doubt whether a function deserves to live in
`utility`, ask: would another brick plausibly want this? If
yes, hoist it.

## References

- [ADR-0011](../../adr/0011-one-component-per-third-party-library.md) —
  One component per third-party library
- [code-style.md](code-style.md)
- [weavejester/medley](https://github.com/weavejester/medley)
- `utility` brick interface
