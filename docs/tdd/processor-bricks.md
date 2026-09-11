# Processor bricks

> **Status: implemented.**

## Objective

Every domain that owns an FDB-backed write — cash accounts,
parties, payments, transactions, interest, IDV — is implemented
as a *domain component* (`components/X/`) hosted by its
group's *processors base* (`bases/financial-processors/` or
`bases/operational-processors/`) inside a combined processor
service — see
[ADR-0019](../adr/0019-processor-packaging.md). This TDD
describes the file layout inside the component, how FDB
transactions thread through it, and where rejections originate.

In scope: the internal architecture of a `X` component
(commands, core, domain, store, events); the `txn-or-config`
parameter convention; the FDB-isolation rule; the rejection
origin rule.

Out of scope: the command / reply / event envelope flow on the
message bus — see
[tdd/transaction-processing.md](transaction-processing.md). The
changelog and relay mechanics themselves — see
[ADR-0021](../adr/0021-changelog-relay.md). The
component-interface conventions in general — see
[recipes/code/components.md](../recipes/code/components.md).

## Background

A processor brick is the unit that translates a command envelope
("open this account", "settle this payment") into a single
atomic FDB write plus the events that flow from it. Three forces
shape its internals:

- **FDB transactions are the atomicity primitive.** Anything
  that must commit or roll back together — possibly spanning
  multiple bricks' stores — has to run inside one FDB
  transaction. The function-call graph has to thread that
  transaction through every participant.
- **FDB should not leak.** Most of the code in a processor is
  not about FDB — it's about validating inputs, applying
  policies, deriving new state. That code should be testable and
  reviewable without knowing FDB exists.
- **Rejections are domain decisions, not infrastructure
  faults.** A "policy denies this transfer" rejection has to be
  separated from a "FDB transaction aborted" infrastructure
  failure, because they map to different envelope statuses and
  HTTP families — see
  [tdd/transaction-processing.md](transaction-processing.md).

The conventions below are how every `X` component answers
those three forces.

## Solution

### File layout

```
components/X/
  src/com/repldriven/queenswood/X/
    interface.clj      # public surface, docstrings
    commands.clj       # Processor protocol impl, command dispatch
    core.clj           # orchestration: store + domain + cross-brick
    domain.clj         # pure logic, the rejection origin
    store.clj          # sole FDB layer, all fdb/* requires
    events.clj         # Processor impl, event dispatch
    validation.clj     # (optional) predicate-style validators
    system.clj         # defcomponents :processor + :event-processor

bases/<group>-processors/    # one per processor group
  src/com/repldriven/queenswood/<group>_processors/
    main.clj           # entry point
    system.clj         # bare-require bundle for the group
```

The base contains no business logic — its `system.clj` is a
bundle of `require` forms so the group's component-kinds are
registered, and `main.clj` starts the system per
[recipes/code/bases.md](../recipes/code/bases.md). Which processors a
given service actually runs is decided by its project's
`application.yml`: a new processor adds its brick to the group
its boundary dictates — financial or operational, per
[ADR-0019](../adr/0019-processor-packaging.md) — wiring its
`bank/X.yml` system config, message-bus entries, bundle require, and
deps into that group's project and base instead of scaffolding a
base and service of its own.

### The `txn-or-config` parameter

Every function that may touch FDB — at any layer — takes a
transaction-like parameter as its first argument. By convention
this parameter is named `txn`. It accepts one of two shapes:

- A live `Txn` value (an open FDB transaction).
- A *config map* with `:record-db` and `:record-store`, which
  the FDB layer can use to **open** a fresh transaction.

`fdb/transact` from the `fdb` brick is the only function that
distinguishes the two. Given a live Txn, it runs the body with
that same Txn (reuse). Given a config, it opens a fresh FDB
transaction, runs the body, and commits (open).

```clojure
(defn transact
  "Runs f within a transaction. f receives a Txn. Given an
  existing Txn, reuses it; given a config map with
  :record-db and :record-store, opens a fresh FDB
  transaction."
  ([txn-or-config f] ...)
  ([txn-or-config f category message] ...))
```

Every call site below — store, core, cross-brick interface,
commands entry point — passes `txn` straight through. The
top-of-stack call site (the command dispatcher) holds a config;
the moment that config enters `fdb/transact` once, every
function further down receives a Txn instead, and additional
`fdb/transact` calls just reuse it.

This is what gives the architecture its atomicity:

- A single FDB transaction can span many functions, including
  calls into other bricks' interfaces (`balances/new-balances`,
  `parties/get-party`, `policy/get-effective-policies`).
- Each of those bricks' store functions also start with
  `fdb/transact txn ...`, but because `txn` is already a live
  Txn, they reuse it rather than opening a new one.
- The whole graph commits together, or nothing does.

The trade-off is that **every function that participates in the
graph must accept and forward `txn`** — including read-only
helpers and pure-orchestration code in `core.clj`. Omitting it
breaks atomicity silently, because a missing forward turns one
transaction into two.

### `store.clj` — the sole FDB layer

`store.clj` is the only file in a processor brick that requires
`com.repldriven.queenswood.fdb.interface`. Every operation it exposes
wraps its body in `fdb/transact`, and a brick whose callers need to
span several store calls in one transaction re-exports it —
`(def transact fdb/transact)` — rather than letting `core.clj` reach
past the store to `fdb` itself.

The rule is not processor-specific, even though it is written down
here. Any brick that owns a `store.clj` keeps its `fdb` requires
there: relays and query bricks have no `commands.clj`, so they are not
processors, but the reason for the confinement — one place to change
if the store ever changes — applies to them identically.

```clojure
(ns com.repldriven.queenswood.cash-account.store
  (:require
    [com.repldriven.queenswood.schema.interface :as schema]
    [com.repldriven.mono.error.interface :as error :refer [let-nom>]]
    [com.repldriven.queenswood.fdb.interface :as fdb]))

(def ^:private store-name "cash-accounts")

(def transact fdb/transact)

(defn save-account
  [txn account changelog]
  (fdb/transact
   txn
   (fn [txn]
     (let [store (fdb/open txn store-name)]
       (let-nom>
         [_ (fdb/save-record store (schema/CashAccount->java account))
          _ (fdb/write-changelog store store-name (:account-id account)
                                 (schema/CashAccountChangelog->pb changelog))]
         nil)))
   :cash-account/save
   "Failed to save account"))
```

Three conventions:

- `(def transact fdb/transact)` re-exports the transaction
  primitive so callers say `store/transact`, not `fdb/transact`.
  This keeps the `fdb` require contained to `store.clj`.
- Every fn takes `txn` first and immediately wraps in
  `fdb/transact txn ...` — so the same fn is callable from
  inside a parent transaction (reuse) or from outside (open).
- The third and fourth arguments to `fdb/transact` are an
  anomaly category and message. Low-level FDB faults surface as
  `:error/anomaly` with those identifiers, never as raw
  exceptions.

Schema translation (`schema/X->pb`, `schema/pb->X`,
`schema/X->java`) belongs in `store.clj` too — it is the
serialisation boundary between FDB records and domain data
shapes. The changelog envelope is the exception in a brick that
emits more than one event kind: there `core.clj` picks the
builder and passes the encoded entry to `store/save`, because
the store cannot know which of them a given call means. A brick
with a single changelog event keeps building the envelope in
`store.clj`.

### `domain.clj` — pure logic, the canonical rejection site

`domain.clj` has no FDB requires, no store requires, no schema
requires. It works on plain Clojure data: takes domain entities
and inputs, returns domain entities or `:rejection/anomaly`.

```clojure
(defn open-account
  [data product party address-fountain-fn aggregates policies]
  (let-nom>
    [_ (validation/valid-product? product)
     _ (validation/valid-currency? currency product)
     _ (validation/valid-party? party)
     _ (check-capability :cash-account-action-open account-type policies)
     _ (policy/check-limit policies :cash-account {...})
     payment-addresses (new-addresses product address-fountain-fn)]
    {...account map...}))
```

This is the canonical place rejections originate. Concretely:

- Validation failures (`error/reject :cash-account/no-payment-schemes
  ...`).
- Policy denials (capability or limit checks returning
  rejection anomalies).
- Domain pre-conditions decidable from input data alone
  ("currency not allowed for this product").

Rejections from the domain map to `REJECTED` envelopes and 4xx
HTTP — see
[tdd/transaction-processing.md](transaction-processing.md). They
are not the same as `:error/anomaly` from the store layer, which
map to `FAILED` and 5xx.

Three other places `error/reject` may legitimately appear, in
strict order of preference:

- **`commands.clj`** — sanctioned site for *protocol-level*
  rejections (`:X/unknown-command`, "missing schema for
  command"). These originate at the message-protocol boundary,
  not the domain, and must be enforced here so unknown commands
  don't fall through silently.
- **`validation.clj`** — supporting predicates called *from*
  `domain.clj`. Same standing as `domain.clj` for this rule.
- **`core.clj`** — tolerated for *read-derived* rejections
  ("already-submitted", "duplicate-X", "no-settlement" — checks
  that need an FDB read to detect). New code should prefer
  pushing the check into `domain.clj` once the read has happened
  and the relevant data is in hand; existing `core.clj`
  rejections are kept on a watch list rather than migrated
  immediately.

Where rejections must **not** appear:

- **`store.clj`** — the store layer raises `:error/anomaly` for
  infra faults via `fdb/transact`'s category/message args, but
  must not produce `:rejection/anomaly`. A "not-found" read
  returns `nil`; the caller decides whether that's a rejection.
  An idempotency check ("already-exists") belongs in
  `core.clj` after the read, not in the read itself.
- **`interface.clj`**, **`events.clj`**, **`system.clj`** —
  these have no business deciding rejections at all.

Where the domain needs *fresh, allocation-style* effects (a
counter for a payment address, a generated id), `core.clj`
passes those in as a closure — see `address-fountain-fn` above.
The domain itself stays pure.

### `core.clj` — orchestrator

`core.clj` is the choreography. It opens a `store/transact`
block, reads the data it needs via `store/*` and other bricks'
interfaces (all forwarding `txn`), calls `domain/*` with the
read data to compute new state or get a rejection, and writes
back via `store/*`. It threads `let-nom>` through the whole
thing so the first rejection or error short-circuits.

```clojure
(defn open-account
  ([txn data]
   (open-account txn data {}))
  ([txn data opts]
   (store/transact
    txn
    (fn [txn]
      (let [{:keys [organization-id party-id product-id currency]} data]
        (let-nom>
          [policies (get-policies txn organization-id opts)
           party (parties/get-party txn organization-id party-id)
           product (products/get-product txn organization-id product-id)
           product-version (products/published-version product)
           _ (when (nil? product-version)
               (error/reject :cash-account/open
                             {:message "Product is not published"
                              :product-id product-id}))
           aggregates (counts txn organization-id ...)
           account (domain/open-account
                    data product-version party
                    (fn [counter]
                      (store/allocate-payment-address txn counter))
                    aggregates policies)
           _ (balances/new-balances
              txn (domain/opening-balances account currency product-version))
           _ (store/save-account txn account {...changelog...})]
          account))))))
```

Three things to note:

- The outermost `store/transact txn (fn [txn] ...)` is what
  guarantees atomicity for the whole operation. Inner store and
  cross-brick calls reuse this `txn`.
- Cross-brick reads and writes (`parties/get-party`,
  `products/get-product`, `balances/new-balances`,
  `policy/get-effective-policies`) take the same `txn` —
  meaning a single FDB transaction spans multiple bricks'
  stores. The `:interface.clj` of each consumed brick has `txn`
  as its first parameter for exactly this reason.
- `domain/open-account` does **not** receive `txn`. Effects it
  needs (here, the payment-address counter) are passed in as
  thunks.

### `events.clj` — reacting to another brick's transition

A brick reacts to another brick's state change by consuming an
event, not by reading its records. The source store co-commits a
`ChangelogEvent` envelope to its changelog, the relay tier
republishes it to the bus, and `events.clj` implements
`processor/Processor` to handle it — the same protocol
`commands.clj` implements, on an event channel instead of a
command channel.

```clojure
(defn- handle-status-changed
  [config data]
  (let [{:keys [record-db record-store]} config
        bank {:record-db record-db :record-store record-store}
        {:keys [bank-id account-id status-after]} data]
    (core/complete-status-transition bank
                                     bank-id
                                     account-id
                                     (keyword status-after))))

(defn- dispatch
  [config message]
  (let [{:keys [event payload]} message
        {:keys [schemas]} config
        schema (get schemas event)]
    (if-not schema
      (do (log/warnf "Unknown cash-account event: %s" event) nil)
      (let-nom> [data (avro/deserialize-same schema payload)]
        (case event
          "cash-account-status-changed" (handle-status-changed config data)
          (do (log/warnf "Unknown cash-account event: %s" event) nil))))))

(defrecord CashAccountEventProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
```

Three properties matter here, and all three are why this replaced
the in-process changelog watcher it grew out of:

- **No `fdb` require.** `events.clj` receives a config map and
  hands it to `core/*`, which threads it on as `txn-or-config`.
  FDB stays in `store.clj` with no carve-out.
- **No shared transaction.** The handler runs on a bus consumer,
  not inside the transaction that advances a changelog cursor, so
  it is free to open its own transactions and take as long as it
  needs.
- **Silent on redelivery.** Delivery is at-least-once, so the
  source-status guard in `domain.clj` is the idempotency gate: an
  event for a transition already applied finds the entity past
  that state and skips, rather than rejecting.

Wire it as a `<brick>/event-processor` kind wrapped in mono's
`event-processor/event-processor`, which leaves the event
unacknowledged when the handler throws or returns an anomaly, so a
failed lifecycle transition is redriven rather than lost.

### `commands.clj` — message entry point

`commands.clj` implements the `processor/Processor` protocol. It
deserialises the Avro payload, dispatches on `:command`, calls
the matching `core/*` function with `config` as the `txn`
argument, and shapes the response envelope:

```clojure
(def ^:private command-handlers
  {"open-cash-account"
   (fn [config data]
     (->response config (core/open-account config data)))
   "close-cash-account"
   (fn [config data]
     (->response config (core/close-account config data)))
   "get-cash-account"
   (fn [config data]
     (let [{:keys [organization-id account-id]} data]
       (->response config
                   (core/get-account config organization-id account-id))))})

(defrecord CashAccountProcessor [config]
  processor/Processor
    (process [_ message] (dispatch config message)))
```

At this layer, `config` *is* the `txn-or-config` argument —
specifically, it's a config map. The first `fdb/transact` inside
`core/open-account` turns it into a Txn for everything that
follows.

### `system.clj` — wiring

`system.clj` registers two component-kinds via `defcomponents`:

- `:processor` — produces the `CashAccountProcessor` record from
  `commands.clj`, with `:record-db`, `:record-store`, and
  `:schemas` injected as `system/required-component`.
- `:event-processor` — produces the Processor from `events.clj`,
  with `:record-store` injected.

The processor base's own `system.clj` is a bare-require bundle
of the bricks whose component-kinds need to be registered before
`system/start` runs. See
[recipes/code/system-components.md](../recipes/code/system-components.md)
for the `defcomponents` mechanics.

## Invariants

The three rules that make the architecture hang together:

- **FDB is required only in `store.clj`.** No
  other file in the brick may require
  `com.repldriven.queenswood.fdb.interface`. There is no
  carve-out: the `watcher.clj` exception this rule used to
  carry went with the watchers themselves.
- **Rejections originate in `domain.clj`** (or
  `validation.clj` called from it), with `commands.clj` as a
  sanctioned site for protocol-level rejections (unknown
  command, missing schema). `core.clj` rejections for
  read-derived checks (idempotency, post-read pre-conditions)
  are tolerated on a watch list but should migrate to
  `domain.clj` where the input data alone is enough.
  `store.clj`, `interface.clj`, `events.clj`, and `system.clj`
  must not produce rejections. `:error/anomaly` from
  infrastructure faults originates in `store.clj`. Neither is
  raised; both flow up via `let-nom>`.
- **Every function that participates in a transaction takes
  `txn` first and forwards it.** Including pure-orchestration
  helpers in `core.clj`, cross-brick interface calls, and the
  command dispatcher's entry call. A missing forward silently
  splits one transaction into two and breaks atomicity.

## Related

- [tdd/transaction-processing.md](transaction-processing.md) —
  command / reply / event envelope flow on the bus.
- [ADR-0005](../adr/0005-error-handling-with-anomalies.md) —
  rejection vs error vs failure anomaly categories.
- [ADR-0021](../adr/0021-changelog-relay.md) — the relay
  mechanics that `events.clj` consumes from.
- [recipes/code/components.md](../recipes/code/components.md) — general
  brick conventions (interface as the doc surface, etc.).
- [recipes/code/error-handling.md](../recipes/code/error-handling.md) —
  `let-nom>`, `error/reject`, `error/try-nom`.
