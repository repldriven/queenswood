# 5. Error handling with anomalies at interface boundaries
<!-- tessl-plugin: idioms -->

## Status

Accepted.

## Context

A banking system cannot afford unstructured or silent failures.
Exceptions in Clojure (and the Java libraries that underlie most of
the stack) are the default failure mechanism, but they have well-known
weaknesses for a system of this kind:

- They are not data. Logging, counting, routing, and asserting against
  them programmatically is awkward.
- They are control-flow-as-exception. A function that "does the work"
  looks like it succeeded until it didn't, and the type signature
  hides the failure.
- They are easy to mishandle: too-broad catches swallow important
  errors, too-narrow ones let surprises propagate where they
  shouldn't.
- Java interop guarantees a steady stream of checked and unchecked
  exceptions whether we want them or not.

A bank also has more than one *kind* of failure to surface.
Something genuinely going wrong (a database timeout, a bug, a
partition) is not the same as the system correctly deciding "no" (a
policy denial, a missing prerequisite, a duplicate posting), which is
not the same as "the caller has no business asking" (no API key,
wrong tenant). Collapsing all three into one notion of error makes
the API layer's job harder and obscures real bugs.

We need a single project-wide convention that handles all this.

The shortlist:

- **Bare exceptions.** The Clojure default. Rejected for the reasons
  above.
- **Cognitect's anomalies as a data shape**, with hand-rolled
  threading. The data shape is good; the ergonomics aren't there
  without supporting macros.
- **Either-monad libraries.** Heavy buy-in; type-class machinery; not
  idiomatic Clojure for a codebase that doesn't otherwise lean that
  way.
- **Condition systems** (Common Lisp style). More machinery; not
  idiomatic in Clojure; harder for contributors to reason about.
- **Anomalies via [`de.otto/nom`](https://github.com/otto-de/nom)**, a
  thin tag-vector convention with macros (`let-nom>`, `nom->`,
  `try-nom`, and friends) that make anomaly-returning code ergonomic.
  We layer three semantic anomaly tags on top to distinguish the
  kinds of failure listed above.

## Decision

We will use anomalies as the failure-return convention at every
component interface boundary, via the `error` brick (which sits on
top of `de.otto/nom`).

The rules:

1. Functions in `interface.clj` MUST NOT throw. They return either a
   value or an anomaly.
2. Three anomaly kinds, each a distinct nom tag, with matching
   constructors and predicates in the `error` interface:
   - `:error/anomaly` — something genuinely went wrong (infrastructure
     fault, bug, exception caught at a boundary). Constructor:
     `error/fail`.
   - `:rejection/anomaly` — the system worked correctly and decided
     not to perform the requested operation (policy denial, missing
     prerequisite, duplicate, conflict). Constructor: `error/reject`.
   - `:unauthorized/anomaly` — the caller is not permitted to make
     this call at all. Constructor: `error/unauthorized`.

   This split lets the API layer (and the command envelope, separate
   ADR) map cleanly to HTTP status families: errors become 5xx,
   rejections become 4xx, unauthorized becomes 401/403.
3. `try-catch` is forbidden in component code. Use `error/try-nom`
   (catch all) or `error/try-nom-ex` (catch a specific exception
   type) to convert exceptions to anomalies. `try-nom` automatically
   attaches the underlying exception and stack trace to the anomaly
   payload, so debugging information is not lost.
4. Anomaly category names the *call site* when nobody outside the
   process can act on the failure — e.g. `:http-client/request`, not
   `:http-client/failed`. There the category answers "where did this
   come from" and the payload answers "what went wrong". When a caller
   *can* act, the category names the problem instead, and the call site
   moves to the payload. See "Naming a category" below.
5. Anomaly payloads MUST contain `:message`. Pass a string as
   shorthand or a map for additional context.
6. Compose anomaly-returning calls with `error/let-nom>` (monadic
   let), `error/nom->` (threading), and `error/nom-do>` (sequential
   side effects). All three short-circuit on the first anomaly.

The discipline applies to every brick. Internal namespaces inside a
brick may use exceptions where it suits, but the conversion happens
at the boundary, never beyond it.

```clojure
(defn fetch-account
  [bank account-id]
  (error/let-nom> [account (store/get bank account-id)
                   _       (when-not account
                             (error/reject :bank/account-not-found
                                           {:message "no such account"
                                            :account-id account-id}))
                   balance (balance/available bank account-id)]
    (assoc account :available balance)))
```

This convention is inherited from `mono` (ADR-0001) and is the most
load-bearing piece of mono that Queenswood depends on. Every
interface boundary, every component, every test relies on it.

### Naming a category

Rule 4 originally said call site, always. That was written while a
category was an internal diagnostic. The single unified API
([ADR-0013](0013-single-unified-api.md),
[ADR-0014](0014-openapi-3x-compliance.md)) then made it the RFC 9457
`type` on every problem response, and RFC 9457 defines `type` as
identifying the *problem*, not its origin. The two conventions were
settled independently and collide wherever an anomaly reaches a client.

The line is whether anyone outside the process can act on it.

**A rejection always names the problem.** A 4xx exists to tell a caller
what they got wrong so they can fix it and retry — `:bank/invalid-status`,
`:cash-account-product/currency-not-allowed`,
`:cash-account-migration/notice-after-due`. A rejection named for its
call site would be useless to the caller and would defeat the
rejection-to-status mapping, which reads the category. This was already
universal practice before it was written down here.

**An actionable error names the problem.** Most 5xx are opaque to a
caller — the server broke, and there is nothing to do but report it —
so those keep call-site naming and the payload carries the detail. But
a failure that says *retry* is actionable, and its category has to say
so: `:fdb/contention` and `:fdb/timeout` come from the Record Layer's
own type hierarchy, and a client branches on them. Both map to 503,
because both mean back off and retry — they differ in what they say
about the cluster, not in what the caller should do, and under load
retries turn one into the other. The distinction rides `type`, which
is the case for naming the problem there rather than splitting the
status.

**Everything else names the call site**, and the stability argument
holds: call sites rarely change, failure modes proliferate. The
exception is bounded on purpose — a closed set the storage layer can
name, with call-site naming as the fallback for everything it cannot.
`:http-client/failed` is still wrong, because nothing can act on it
that could not act on `:http-client/request`. That pair is
illustrative, not a defect to go and fix — `mono`'s http-client
already names by call site (`:http-client/request`,
`:http-client/body-parse`). Both halves name the same brick so the
example isolates the naming choice.

When a category names the problem, the call site is not discarded — it
moves to the payload (`:operation`) so logs still say where the failure
arose.

## Consequences

Easier:

- Failure is a first-class return value. Every caller engages with
  it; nothing slips by silently.
- The error/rejection/unauthorized split gives the API layer the
  exact information it needs to choose an HTTP status without
  inspecting payload contents. The command envelope uses the same
  split.
- Anomalies are data. Logging, counting, routing, and asserting
  against them in tests is straightforward.
- The composition macros let happy-path code stay readable while
  failures short-circuit cleanly. `let-nom>` reads like `let`;
  `nom->` reads like `->`. Clj-kondo lints them as their core
  equivalents.
- `try-nom` attaches the underlying exception and stack trace to the
  anomaly payload — so the debugging information you would have got
  from a thrown exception is still there, just structured.
- Test code asserts "this returns an anomaly" without try/catch
  scaffolding; the `nom-test>` macro composes the same way `let-nom>`
  does but fails fast on any anomaly.
- Each component can be reasoned about independently because its
  interface contract includes the anomaly-or-value guarantee.

Harder:

- The discipline must be learned and applied consistently. A single
  throwing interface function poisons the contract for every caller.
- Java interop is exception-heavy. Every Java boundary needs
  `try-nom` or `try-nom-ex`. Forgetting one breaks the convention.
- The naming convention (category = call site, not failure mode) is
  non-obvious to newcomers and tempting to break.
- The three-kind split is mostly a benefit, but it adds a third
  question to every interface function: is this case an error, a
  rejection, or an authorization issue? Wrong answer leaks into HTTP
  statuses that mislead callers.
- Three-state thinking ("value or anomaly") is overhead compared
  with just-throw-on-bad. It pays off at scale, but the upfront
  cognitive cost is real.
