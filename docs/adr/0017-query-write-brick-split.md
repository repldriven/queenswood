# 17. Query/write brick split for domain components

<!-- tessl-plugin: design -->

## Status

Accepted. Rolling out incrementally — `cash-account` is the first
domain component split; the rest follow as ordinary work.

## Context

Queenswood is CQRS-shaped: the API reads by calling a `X`
component directly and synchronously, and writes by sending a command
over the bus to a processor. But the shape is a convention, not a
boundary. Query and write functions sit side by side in one
`interface.clj`, and every API handler already holds a write-capable
FDB handle (`{:record-db :record-store}`) — it needs one to read. So a
write is always one function call away from any handler: nothing stops
`api` calling `cash-accounts/new-account` directly instead of
issuing a command. In practice some writes already bypass the bus this
way.

We want the read/write separation to be structural, not just
disciplined: the API can only call read paths, and every state change
is a command consumed by a processor. This applies to the domains
that earn a command path — which writes those are is decided by
[ADR-0018](0018-command-writes-are-earned.md); synchronous
configuration writes are exempt by design.

The shortlist of how to enforce it:

- **Sub-namespaces inside one interface** (`X.interface.query` /
  `.command`). Rejected — Polylith includes another brick's *whole*
  interface. Requiring `X.interface` hands you the writes too, so
  this documents the split without enforcing it.
- **One interface, mark writes with metadata or naming, lint the API.**
  Rejected — classification is heuristic and there is no structural
  teeth; a write is still reachable from the API's classpath.
- **A shared `X-store` brick under both a read brick and a write
  brick.** Rejected — the store interface exposes writes, so the read
  brick regains (curated) write reach. It adds a third brick per
  domain and improves nothing at the boundary we care about.
- **A separate `X-query` brick.** A first-class component whose
  interface exposes only reads. The API requires it; the write brick
  reuses its reads inside its own transactions. Chosen.

## Decision

Each domain component splits into two first-class Polylith bricks,
`X-query` and `X`.

What each brick holds, how they are named, and how the split is enforced:

- `X-query` — reads only (`get-*`, `find-*`, `count-*`), plus the
  read primitives the write side needs inside a transaction. This is
  the only cash-account-style brick `api` may require.
- `X` — commands, core writes, domain, write-side store, events.
  It depends on `X-query` and calls its read fns inside its own
  FDB transactions, passing the live `txn` — the same cross-brick
  read-with-live-txn idiom already used across the codebase (a read fn
  takes `txn` first and joins the caller's transaction).
- **Naming polarity.** The write side keeps the plain `X` name; the
  read carve-out takes the `-query` suffix. Writes are the core domain
  brick; the query brick is a read projection over the same records.
  This also avoids renaming the existing brick and re-pointing every
  write caller. The guardrail keys off the sibling's existence:
  `components/X-query/` marks `X` as a guarded write brick.
- **Defense in depth (later stage).** Splitting the brick makes the
  API unable to *name* a write, but a `X-query` still requires
  `fdb.interface`, which exposes write verbs alongside reads. A later
  stage splits `fdb` into `fdb-query` (read verbs) and a write side
  over a shared core (`Txn`, `transact`, `open`, `ctx->txn`), so the
  read stack has no write verb in scope top to bottom.
- **Two tiers of enforcement, both real:**
  - **Guardrail** — a pre-commit guardrail check
    (`scripts/hooks/enforce-idioms.sh`) fails if `api` request code
    requires a write brick's interface that has a `-query` sibling.
    This holds in every project, including `development`, which
    includes every brick and so cannot rely on project exclusion. The
    `system.clj` registration bundle is exempt: it bare-requires
    interfaces to register component-kinds, not to call them. The
    check asserts its own base path and namespace prefix before
    running, and fails if either matches nothing. It is two greps over
    names a rename empties, and an empty candidate set is
    indistinguishable from a clean one — the `bank-api` → `api` and
    `mono` → `queenswood` renames left it inert and passing.
  - **`poly check`** — once a service project no longer lists the
    write brick, `poly check` hard-fails any reference to it from that
    project. This is gated on removing the remaining synchronous
    writers from the API's classpath (for cash-account, `bank`'s
    house-account open), so it lands per domain as those writers move
    to the bus.

## Consequences

Easier:

- The API's read surface is structural. A handler that tries to write
  a split domain either fails the guardrail or, once the write brick
  leaves the project, fails `poly check`.
- Reads have a single home. The write side reuses `X-query`
  rather than duplicating read store fns, and there is no read/write
  drift within a brick.
- The boundary is self-documenting: the interface a caller requires
  states whether it is allowed to write.

Harder:

- Two bricks per domain instead of one, and every reader of the moved
  reads must repoint to the `-query` interface (there is no
  re-export). A brick that both reads and writes ends up requiring
  both interfaces.
- The write brick depends on the query brick — an extra arrow, though
  it mirrors the existing cross-brick read idiom.
- One FDB store-contract constant (`store-name`) is duplicated across
  the two `store.clj` files, since neither may reach past the other's
  interface. Kept in sync by a paired comment.
- Full structural exclusion (`poly check`) trails the split: while a
  synchronous writer keeps the write brick on the API classpath, the
  guardrail is the only teeth for that domain.
