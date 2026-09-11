---
name: check-processors
description: Scan Queenswood processor bricks (a `components/X` with both `commands.clj` and `store.clj`) for violations of the processor-brick conventions. The `fdb`-outside-`store.clj` check runs wider, over every brick that owns a `store.clj` — relays and query bricks have no `commands.clj`, so they are not processors but the rule still binds. Checks: `fdb` requires leaking outside `store.clj`, the presence of a `watcher.clj` at all, `domain.clj` requiring `fdb` / store / schema, `error/reject` appearing in `store.clj` / `interface.clj` / `events.clj` / `system.clj` (hard fail) or in `core.clj` (advisory watch list), `domain.clj` functions accepting a `txn` parameter, and `schema/X->Y` type-conversion calls leaking into `domain.clj`. Report findings against `docs/tdd/processor-bricks.md`. Use as a per-brick lint ("check processors", "audit processor bricks", "lint processor conventions", "do my processor bricks follow the pattern?", "check processor invariants").
allowed-tools: Bash
---

# check-processors

Runs every brick-level invariant listed in
[docs/tdd/processor-bricks.md](../../../docs/tdd/processor-bricks.md)
across the processor bricks in scope and reports findings.

The TDD is the source of truth for *what's wrong* and *how to
fix it*. This skill is the runner.

A "processor brick" here means any `components/X` that has
both a `commands.clj` and a `store.clj` — that is, a brick that
implements the `Processor` protocol and owns FDB-backed writes.

## Scope

Default scope: processor bricks with changes on the current
branch (commits ahead of `main`, plus working-tree modifications
and untracked files). The whole brick is audited if any of its
files changed.

Overrides:

- `bash .claude/skills/check-processors/checks.sh --staged` —
  bricks with staged changes only
- `bash .claude/skills/check-processors/checks.sh --all` —
  every processor brick in the repo
- `bash .claude/skills/check-processors/checks.sh <brick> ...` —
  explicit brick names (e.g. `cash-account party`)

## Checks

| Check | What it flags | Source invariant |
|-------|---------------|-------------------|
| `fdb-leak` | a require of `com.repldriven.queenswood.fdb.interface` in any file other than `store.clj` | TDD: "FDB is required only in `store.clj`" |
| `watcher-present` | a `watcher.clj` existing at all — the changelog relay replaced watchers, and reactive work belongs in `events.clj` | ADR-0021: no handler runs inside the changelog checkpoint transaction |
| `domain-impurity` | a require of `com.repldriven.queenswood.fdb.interface`, `com.repldriven.queenswood.schema.interface`, or this brick's own `.store` in `domain.clj` | TDD: "`domain.clj` requires no `fdb`, no `store`, no `schema`" |
| `rejection-misplaced` | `error/reject` in `store.clj`, `interface.clj`, `events.clj`, or `system.clj` — hard fail. (`commands.clj`, `domain.clj`, `validation.clj` are the sanctioned sites and are not scanned.) | TDD: "Rejections originate in `domain.clj`, with `commands.clj` as a sanctioned site for protocol-level rejections" |
| `rejection-in-core` | `error/reject` in `core.clj` — advisory watch list, tolerated for read-derived checks but new code should prefer `domain.clj` | TDD: "`core.clj` rejections for read-derived checks are tolerated but should migrate to `domain.clj`" |
| `domain-takes-txn` | `txn` appearing anywhere in `domain.clj` (defn arg vector, let binding, body) | TDD: "`domain.clj` does not receive `txn`" |
| `domain-schema-leak` | `schema/<Type>-><...>` type-conversion calls in `domain.clj` | TDD: "`store.clj` schema-translates at the boundary" |

## What to do with the findings

Treat every `PASS` line as a clean check. For each `FAIL`:

1. Open `docs/tdd/processor-bricks.md`; it explains the why and
   the "OK" pattern for the relevant invariant.
2. Apply the fix — don't restate the TDD in the response.
3. Re-run the script to confirm.

Per-check fix hints:

- **`fdb-leak`** — move the FDB-touching code into `store.clj`
  and expose it from there. The non-`store.clj` file should
  call the new `store/*` fn (passing `txn`), not `fdb/*`
  directly.
- **`watcher-present`** — delete the watcher and move its
  reaction into `events.clj`, keyed off the event the relay
  republishes from the originating brick's changelog. Wire the
  consumer as a `<brick>/event-processor` kind wrapped in
  mono's `event-processor/event-processor`, which leaves the
  event unacknowledged when the handler throws or returns an
  anomaly, so it is redriven rather than lost.
- **`domain-impurity`** — remove the require. If domain needs
  data from the store, the caller in `core.clj` should fetch
  it and pass plain data into the domain fn. If domain needs
  a schema-translated value, the translation happens in
  `store.clj`, not `domain.clj`.
- **`rejection-misplaced`** — for `store.clj`, change the
  lookup to return `nil` (or the record) and let `core.clj`
  decide whether the absence is a rejection, or push the
  derivation into `domain.clj` after the read. Idempotency
  checks like `:X/already-exists` are domain decisions about
  stored state — they belong in `core.clj` (tolerated) or
  `domain.clj` (preferred), not in the read itself. For
  `interface.clj` / `events.clj` / `system.clj`, refactor the
  rejection-producing logic out into `core.clj` or
  `domain.clj`.
- **`rejection-in-core`** — watch list. The rejection is
  tolerated when it requires an FDB read to detect
  (idempotency, post-read pre-conditions). If the rejection
  could be derived from the input data plus the read result,
  prefer moving it into `domain.clj` — `core.clj` does the
  read, then passes the data to a pure `domain.clj` fn that
  produces the rejection if applicable.
- **`domain-takes-txn`** — refactor the domain fn to take
  plain data instead. If it needs a side-effect like a counter
  or generated id, the caller in `core.clj` passes that in as
  a closure (see `address-fountain-fn` in
  `cash-account/domain.clj` for the pattern).
- **`domain-schema-leak`** — move the conversion into
  `store.clj`. Domain works on Clojure-shaped maps; the
  conversion to/from FDB record types happens at the storage
  boundary.

## Reporting

Report results in this shape:

- A header line naming the bricks in scope (e.g.
  `Scope: branch — 2 brick(s): cash-account, party`).
- One line per check: `fdb-leak: 0`, `domain-impurity: 2 (in
  payment)`, …
- For each non-empty check, the file:line refs from the
  script's output and a per-item suggested fix from the hints
  above.
- Stop once everything's clean.

If everything passes, say so in one line.

## Editing the script

The actual checks live in `checks.sh` in this skill's directory.
Edit it to add a check, tighten a regex, or adjust the file
scope. Keep new checks in the `section` + `report` shape so the
output format stays consistent across runs.
