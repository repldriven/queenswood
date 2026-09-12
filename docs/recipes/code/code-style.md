# Code style

<!-- tessl-plugin: idioms -->

## Problem

You want to write Clojure that fits the project's conventions.

## Solution

We use zprint for formatting, clj-kondo for linting, and a
small set of project-specific conventions for namespaces,
naming, destructuring, and a few common pitfalls.

### Formatting

zprint formats all Clojure source. Configuration is in
`.zprint.edn` at the workspace root.

- Line width: **80 characters.**
- The pre-commit hook auto-formats staged files; you don't
  normally run zprint manually.
- zprint does not reflow string content, so multi-line
  docstrings need manual wrapping at 80:

```clojure
(defn my-fn
  "First line, kept within 80 chars.

  Further detail on subsequent lines, also wrapped at 80. Use
  blank lines to separate paragraphs."
  [args]
  body)
```

### Namespace requires

A **bare** require is one with no `:as` and no `:refer` —
loaded purely for its side effect of extending multimethods
(system-component registrations). Bare requires take the
bracketed form, `[com.example.ns]`, never the unbracketed
`com.example.ns`. The earlier convention used unbracketed
namespaces; we walked that back, and the bracket is what makes
a deliberate side-effecting require visually distinct from a
line someone forgot to alias.

Order `:require` entries innermost to outermost, blank line
between groups, alphabetical within each group. A group with
no entries simply doesn't appear.

1. **Own `system` namespace** — this brick registering its own
   component kinds. Alone, because it is the only require that
   is about the file's own brick rather than a dependency.
2. **Extension namespaces, Queenswood** — bare requires
   registering *other* bricks' component kinds.
3. **Extension namespaces, `mono`** — the same, from upstream.
4. **This file's own package** — the files sitting beside it.
   In a test namespace this is where the SUT goes.
5. **Rest of the brick** — other packages under the same
   brick. Only bases nest deeply enough for this to appear; in
   a flat component the brick *is* the package, so groups 4
   and 5 collapse into one.
6. **Other Queenswood interfaces** — `interface.clj` of other
   bricks in this workspace. Interfaces only.
7. **`mono` interfaces** — the upstream dependency, so further
   out than our own bricks.
8. **External libraries.**
9. **Standard libraries** (`clojure.*`).

Groups 1–3 are the bare requires, 4–9 the rest. Both halves
run the same way outwards: this file, then the brick, then
Queenswood, then everything beyond it.

See [system-components.md](system-components.md) for the test
bundling pattern.

A production namespace — `onfido-adapter`'s entry point, whose
require list is mostly the extension bundle that registers the
component kinds its `application.yml` names:

```clojure
(ns com.repldriven.queenswood.onfido-adapter.main
  (:require
    [com.repldriven.queenswood.onfido-adapter.system]

    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.onfido-relay.interface]
    [com.repldriven.queenswood.onfido-webhook.interface]
    [com.repldriven.queenswood.schema.interface]

    [com.repldriven.mono.avro.interface]
    [com.repldriven.mono.command-processor.interface]
    [com.repldriven.mono.kafka.interface]
    [com.repldriven.mono.message-bus.interface]
    [com.repldriven.mono.server.interface]
    [com.repldriven.mono.telemetry.interface]

    [com.repldriven.queenswood.onfido-adapter.api :as api]

    [com.repldriven.mono.cli.interface :as cli]
    [com.repldriven.mono.env.interface :as env]
    [com.repldriven.mono.error.interface :as error :refer [nom->]]
    [com.repldriven.mono.log.interface :as log]
    [com.repldriven.mono.system.interface :as system])
  (:gen-class))
```

A test namespace — same spine, with the SUT occupying the
internal slot and `clojure.test` last:

```clojure
(ns ^:eftest/synchronized
    com.repldriven.queenswood.ledger-account.interface-test
  (:require
    [com.repldriven.queenswood.fdb.interface]
    [com.repldriven.queenswood.testcontainers.interface]

    [com.repldriven.queenswood.ledger-account.interface :as SUT]

    [com.repldriven.queenswood.balance.interface :as balance-writes]
    [com.repldriven.queenswood.balance-query.interface :as balances]

    [com.repldriven.mono.error.interface :as error]
    [com.repldriven.mono.system.interface :as system]
    [com.repldriven.mono.test-system.interface :refer
     [with-test-system nom-test>]]

    [clojure.test :refer [deftest is testing]]))
```

A namespace inside a base that nests, where groups 4 and 5 are
both populated — `bank`'s own files, then the rest of `api`:

```clojure
(ns com.repldriven.queenswood.api.bank.routes
  (:require
    [com.repldriven.queenswood.api.bank.commands :as bank-commands]
    [com.repldriven.queenswood.api.bank.examples :refer
     [BankLimitExceeded BankNotFound BankInvalidStatus BankUnknownTier]]
    [com.repldriven.queenswood.api.bank.queries :as queries]

    [com.repldriven.queenswood.api.shared.parameters :as
     shared.parameters]

    [com.repldriven.queenswood.api-schema.interface :refer
     [ErrorResponse]]))
```

In **component interface tests**, alias the SUT (system under
test) as `SUT`, and require no other namespaces from the same
component.

### Naming

Names are narrow. A function in `command` is `process` or
`send`, not `process-command` or `send-command`. The brick name
is the context; functions don't repeat it. Thread macros help
avoid naming intermediate values when the chain is the
meaningful thing.

Side-effecting functions don't take a `!` suffix. Names
describe what a function returns or what effect it has, not
whether it causes one.

Reference: Zachary Tellman's _Elements of Clojure_ on naming —
_"if a function crosses data scope boundaries, there should be
a verb in the name. If it pulls data from another scope, it
should describe the datatype it returns. If it pushes data
into another scope, it should describe the effect it has."_

### Anonymous functions

Use `(fn [x] ...)` for anonymous functions. Avoid the `#(...)`
reader macro form.

```clojure
;; OK
(map (fn [x] (* x 2)) xs)

;; Not OK
(map #(* % 2) xs)
```

### Conditional threading

For `cond->` and `cond->>`, put each predicate and its action
on separate consecutive lines, with a blank line between pairs:

```clojure
(cond-> initial-value
  pred1
  (action1)

  pred2
  (action2))
```

The pattern reads as a sequence of "when this, do that" steps
and keeps each pair visually distinct.

### Destructuring and bindings

Destructure one level at a time inside `let`, not nested in
function arguments. Take the full value as a plain argument
and bind each level explicitly:

```clojure
;; OK
(defn create
  [request]
  (let [{:keys [datasource parameters]} request
        {:keys [body path]} parameters
        {:keys [project-id]} path
        {:strs [account-id service-account]} body
        {:strs [display-name description]} service-account]
    ...))

;; Not OK — nested destructuring in arguments
(defn create
  [{{{:keys [project-id]} :path
     {:strs [account-id]} :body} :parameters}]
  ...)
```

Prefer destructuring over `get` / `get-in` chains. The
destructured form makes the data shape obvious.

When writing `let` bindings, keep each binding's value on the
same line as its name. Let zprint do any wrapping; only wrap
manually if a binding clearly exceeds 80 chars.

```clojure
;; OK — single line per binding; zprint wraps if needed
(let [account (store/get bank id)
      balance (balance/available bank id)]
  ...)

;; Not OK — pre-wrapped; zprint will not reflow it
(let [account
      (store/get bank id)
      balance
      (balance/available bank id)]
  ...)
```

This applies to _any_ form inside `[]` — `let` bindings,
argument vectors, destructuring, literal vectors. zprint
treats manual formatting inside `[]` as deliberate and won't
reflow it, so premature wrapping permanently defeats the
formatter.

### Generating IDs

Use `util/uuidv7` from the `utility` brick — not
`random-uuid`. UUIDv7 is time-ordered, which gives FDB and
other sorted stores better index locality and lets records
sort chronologically for free.

```clojure
(:require [com.repldriven.mono.utility.interface :as util])

;; OK
(util/uuidv7)

;; Not OK
(random-uuid)
```

### Recording timestamps

Use `util/now` from the `utility` brick — not
`(System/currentTimeMillis)`, `(Instant/now)`, or other
platform clock APIs directly. `util/now` returns epoch
milliseconds and is the project's single canonical clock seam,
which keeps tests mockable and behaviour consistent across the
codebase. For RFC 3339 strings, use `util/now-rfc3339`.

```clojure
(:require [com.repldriven.mono.utility.interface :as util])

;; OK
(util/now)
(util/now-rfc3339)

;; Not OK
(System/currentTimeMillis)
(java.time.Instant/now)
```

### Interceptor short-circuit

When a Reitit / Sieppari interceptor needs to short-circuit
the chain (auth 401/403, idempotency replay, etc.), use
`sieppari.context/terminate` — never set `:response` or
`:error` on the context directly:

```clojure
(:require [sieppari.context :as sc])

(fn [ctx]
  (sc/terminate ctx {:status 401
                     :headers {...}
                     :body {...}}))
```

Sieppari does not short-circuit on a `:response` value; the
handler interceptor still runs and reitit's handler step
overwrites whatever you set. `:error` works but the chain
behaviour is confusing — `terminate` is the documented
escape hatch and the only reliable one. `:leave` interceptors
still run normally after termination.

### Linting

clj-kondo is configured in `.clj-kondo/config.edn`. The
pre-commit hook runs it against the full workspace before
allowing a commit. Custom `:lint-as` mappings let kondo treat
project macros as their core equivalents —
`error/let-nom>` lints as `clojure.core/let`, `error/nom->`
lints as `clojure.core/->`, and so on.

## Rules

**MUST:**

- All Clojure source is formatted with zprint (80-column).
- Docstrings are manually wrapped at 80.
- `:require` entries are ordered innermost-to-outermost (own
  `system`, Queenswood extensions, `mono` extensions, own
  package, rest of the brick, other Queenswood interfaces,
  `mono` interfaces, external libraries, standard libraries)
  with blank lines between groups, alphabetical within each
  group. The bare requires and the rest both run outwards the
  same way: this file, then the brick, then Queenswood, then
  beyond.
- Bare requires — no `:as`, no `:refer` — use the bracketed
  form `[com.example.ns]`.
- Component interface tests alias the SUT as `SUT` and
  require no other namespaces from the same component.
- Use `util/uuidv7` for new IDs.
- Use `util/now` for current-time reads (and `util/now-rfc3339`
  for RFC 3339 strings).
- Anonymous functions use `(fn [x] ...)`.
- Destructure one level at a time in `let`.

**MUST NOT:**

- Use `!` suffix on side-effecting function names.
- Use the `#(...)` reader macro for anonymous functions.
- Nest destructuring in function arguments.
- Set `:response` or `:error` on a Sieppari context to
  short-circuit — use `sieppari.context/terminate`.
- Use `random-uuid` or `UUID/randomUUID` for new IDs anywhere outside
  `components/utility/` — that brick is `util/uuidv7`'s one permitted
  caller of the raw primitive.
- Use `(System/currentTimeMillis)`, `(Instant/now)`, or other
  platform clock APIs directly anywhere outside `components/utility/`
  — that brick is `util/now`'s one permitted caller of the raw
  primitive. Go through `util/now` everywhere else.
- Repeat the brick name in function names within that brick
  (`process-command` in `command`, `send-account` in
  `cash-account`, and so on).

**SHOULD:**

- Prefer destructuring over `get` / `get-in` chains.
- Prefer thread macros (`->`, `->>`, `error/nom->`) over
  intermediate `let` bindings when the chain is the
  meaningful value.
- Format `cond->` / `cond->>` with each predicate and action on
  separate lines, blank lines between pairs.
- Prefer `cond->` with `utility/assoc-some` / `assoc-seq` over a
  chain of optional `assoc` calls.
- Aim for referential transparency — pure functions named
  after what they return — except at clear effect boundaries.

## Discussion

The naming rule is the hardest to internalise and pays off
the most. Compound names like `process-command` or
`send-account` read fluently in isolation but are noisy at
every call site. The brick name is already part of the
namespace; repeating it in the function name doubles the
noise without adding meaning.

The `(fn ...)` over `#(...)` rule is about composition.
`#(f %)` and `#(g %1 %2)` are fine in trivial cases, but the
moment you nest or mix arities the form gets ambiguous fast.
Standard `(fn ...)` is always clear.

The destructure-in-let rule is about readable diffs and
keeping cognitive distance short. Nested argument
destructuring puts every concern on the function's signature
line, where a small change touches a lot of layout.
Destructuring in `let` keeps the function signature stable
and makes each level a single-line decision.

The UUIDv7 rule is performance and ordering: time-ordered IDs
give FDB and any other sorted store better locality and
chronological sort for free.

The `util/now` rule is about having a single clock seam.
Reaching for `System/currentTimeMillis` or `Instant/now`
directly scatters the platform dependency through the codebase
and makes time-based behaviour harder to mock in tests. One
wrapper, one place to swap.

## References

- [ADR-0006](../../adr/0006-kebab-case-keyword-keys.md) — Kebab-case
  keyword keys
- [ADR-0012](../../adr/0012-pre-commit-hooks.md) — Pre-commit hooks
- [error-handling.md](error-handling.md)
- [system-components.md](system-components.md)
- _Elements of Clojure_ by Zachary Tellman
