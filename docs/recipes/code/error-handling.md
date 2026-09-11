# Error handling

<!-- tessl-plugin: idioms -->

## Problem

You want to handle failure across component interfaces.

## Solution

We use anomalies — return values that represent failure — instead
of throwing exceptions. The `error` brick wraps
[de.otto/nom](https://github.com/otto-de/nom) and adds three
semantic kinds of anomaly so the API and command envelope can map
to HTTP status families directly.

### Three kinds of anomaly

- **`:error/anomaly`** — something went wrong (infra fault, bug,
  caught exception). Constructor: `error/fail`. Predicate:
  `error/error?`. HTTP family: 5xx.
- **`:rejection/anomaly`** — system worked correctly and decided
  no. Constructor: `error/reject`. Predicate: `error/rejection?`.
  HTTP family: 4xx.
- **`:unauthorized/anomaly`** — caller is not permitted.
  Constructor: `error/unauthorized`. Predicate:
  `error/unauthorized?`. HTTP family: 401 / 403.

`error/anomaly?` matches any of the three. Introspection:
`error/tag` (the kind), `error/kind` (the category),
`error/payload` (the map).

### Constructing anomalies

```clojure
;; Shorthand: string becomes {:message <string>}
(error/fail :ns/category "what failed")

;; Map: pass through, must contain :message
(error/fail :ns/category {:message "what failed" :id some-id})

;; Same shape for reject and unauthorized
(error/reject :bank/account-not-found
              {:message "no such account" :account-id id})
(error/unauthorized :api-key/invalid "missing or stale API key")
```

### Composition

- `error/let-nom>` — monadic `let` that short-circuits on the
  first anomaly. The most common form.
- `error/let-nom` — simpler `let`; body returns an anomaly if any
  binding produces one.
- `error/nom->` — threading `->`, short-circuits on anomaly.
- `error/nom->>` — threading `->>` form.
- `error/with-nom` — wraps a body in an anomaly-capture region.
- `error/nom-do>` — sequential side effects, calls a supplied
  error-fn on the first anomaly.
- `error/nom-let>` — `let-nom>` form with an explicit error-fn.

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

### Catching exceptions

```clojure
;; All exceptions:
(error/try-nom :http-client/request
  "Failed to execute request"
  (do-the-thing))

;; Specific exception type:
(error/try-nom-ex :db/query SQLException
  "Failed to execute query"
  (do-the-thing))
```

`try-nom` automatically attaches the underlying exception and
stack trace to the anomaly payload — debugging information is
preserved.

### Tests

Use `nom-test>` (from `test-system`) for assertion chains that
include anomaly-returning calls:

```clojure
(nom-test> [result1 (operation1)
            _       (is (= expected result1))
            result2 (operation2 result1)
            _       (is (some? result2))])
```

`_` bindings discard the value but still anomaly-check. For a
single check with no further bindings:

```clojure
(nom-test> [_ (operation-that-must-not-fail)])
```

## Rules

**MUST:**

- Functions in `interface.clj` return a value or an anomaly.
- Use `error/try-nom` (catch all) or `error/try-nom-ex` (catch a
  specific type) at every Java/library boundary that throws.
- Anomaly category names the _call site_ (`:http-client/request`)
  when nobody outside the process can act on the failure.
- Anomaly category names the _problem_ when a caller can act on it —
  every rejection (`:bank/invalid-status`), and the closed set of
  storage failures that mean retry (`:fdb/contention`,
  `:fdb/timeout`). The call site moves to the payload as
  `:operation`.
- Anomaly payloads contain `:message`.
- Mark a genuinely unrecoverable `throw` with
  `;; nosemgrep: no-raw-throw` on the line above — the `no-raw-throw`
  semgrep rule (`.config/semgrep/semgrep.yml`) blocks any unmarked one.

**MUST NOT:**

- Throw from `interface.clj` directly or indirectly.
- Use bare `try`/`catch` in component code.
- Name a category by failure mode where nothing can act on the
  distinction (`:http-client/failed` says no more than
  `:http-client/request`).
- Mix the three anomaly kinds — pick the right one for the
  failure being represented.

## Discussion

A bank cannot afford unstructured failure. Exceptions hide flow
(a function looks like it succeeded until it didn't), are not data
(awkward to log, count, route, or assert against), and are easy to
mishandle (too-broad catches swallow important errors; too-narrow
ones let surprises escape). The anomaly discipline turns failure
into a first-class return value that every caller has to engage
with.

The three-kind split is load-bearing because the API layer and the
command envelope need to distinguish a 5xx (infrastructure broke)
from a 4xx (system worked, said no) from a 401/403 (caller
unauthorised) without inspecting payload contents. Collapsing
these into one notion of error makes the upstream routing harder
and obscures real bugs as denials.

The naming convention is non-obvious. Naming by call site keeps
categories stable across refactors — call sites rarely change, while
failure modes proliferate — so it is the default. But a category is
also the RFC 9457 `type` on any problem response that reaches a
client, and there `type` is meant to identify the problem. The two
purposes only conflict when someone outside the process can act on
the failure, which is the test: a rejection tells a caller what to
fix, and a 503 tells them to retry, so both name the problem.
An opaque 500 has no such reader, so it keeps the stable name and
puts the detail in the payload.

`try-nom` automatically captures the underlying exception and
stack trace into the anomaly payload, so the debugging information
you would have got from a thrown exception is still there — just
structured.

## References

- [ADR-0005](../../adr/0005-error-handling-with-anomalies.md) —
  Error handling with anomalies
- [de.otto/nom](https://github.com/otto-de/nom)
- `error` brick interface
- [Cognitect anomalies](https://github.com/cognitect-labs/anomalies)
