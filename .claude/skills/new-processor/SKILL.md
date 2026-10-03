---
name: new-processor
description: Scaffold or extend a Queenswood processor brick — a `components/X/` domain component with the canonical commands/core/domain/store/events/system file set, hosted by its group's processors base (financial or operational) inside a group service (ADR-0036). Threads the `txn-or-config` transaction parameter through every layer, confines `fdb/*` requires to `store.clj`, and originates `:rejection/anomaly` only in `domain.clj`. Use when scaffolding a new processor ("new processor for X", "add a domain brick that handles commands and writes to FDB") or when adding a new command to an existing processor ("add open-Y command to X", "extend payment to handle a new instruction").
---

# new-processor

Helps create or extend a Queenswood processor brick. The
conventions, file roles, and invariants live in
[docs/tdd/processor-bricks.md](../../../docs/tdd/processor-bricks.md);
read that doc first — it is the source of truth.

`components/cash-account/` is the canonical model to copy
from. It exercises every role (commands, core, domain, store,
events, changelog, validation, system) and keeps the
conventions clean.

## Modes

Pick the mode that matches the request before doing anything
else:

1. **Scaffold a new processor.** A whole new domain that owns
   FDB-backed writes (a new `Y` brick, hosted by its group's
   processors base). Follow the scaffold workflow below.
2. **Add a command to an existing processor.** Adds one new
   command handler to a brick that already exists (e.g.
   `archive-cash-account` on `cash-account`). Skip
   scaffolding; touch `commands.clj`, `core.clj`, `domain.clj`,
   `store.clj` only as the new behaviour requires.
3. **Modify an existing processor.** Behaviour change, bug fix,
   or refactor. Use the **Invariants** checklist below to keep
   the change inside the conventions.

If unclear which mode, ask before proceeding.

## Scaffold workflow

For a brand-new processor `Y`, hosted by an existing group
processors base:

1. **Confirm scope with the user.** What domain entity does the
   brick own? Which commands does it expose at launch? What
   downstream writes need to happen in the same FDB transaction
   (which other bricks' interfaces will be called)?

2. **Create the component skeleton** at `components/Y/`,
   copying from `components/cash-account/`:

   - `src/com/repldriven/queenswood/Y/interface.clj` — public
     surface. One-paragraph ns docstring per
     [ADR-0015](../../../docs/adr/0015-comments-and-docstrings.md).
     Forwards to `core/*`. Add docstrings to each public fn
     listing args and what it returns (value vs anomaly).
   - `src/com/repldriven/queenswood/Y/commands.clj` — implements
     `processor/Processor`. Holds the command-name → handler map.
     Deserialises Avro payloads, dispatches to `core/*`, shapes
     the response envelope.
   - `src/com/repldriven/queenswood/Y/core.clj` — orchestrator.
     Every public fn wraps `store/transact txn (fn [txn] ...)`.
     Composes via `let-nom>`. Reads via `store/*` and other
     bricks' interfaces (forwarding `txn`). Calls `domain/*`
     with pure data. Writes via `store/*`.
   - `src/com/repldriven/queenswood/Y/domain.clj` — pure logic.
     No fdb, no store, no schema requires. Returns domain
     entities or `:rejection/anomaly` via `error/reject`. All
     validations and policy checks originate here.
   - `src/com/repldriven/queenswood/Y/store.clj` — sole FDB
     layer. `(def transact fdb/transact)` re-export. Every fn
     takes `txn` first and immediately wraps in
     `fdb/transact txn (fn [txn] ...)`. Uses `fdb/open
     store-name` inside the body. Schema translation
     (`schema/X->pb`, `schema/pb->X`, `schema/X->java`) lives
     here.
   - `src/com/repldriven/queenswood/Y/changelog.clj` — builds
     the `ChangelogEvent` envelope co-committed with a write,
     so the relay has a payload to republish.
   - `src/com/repldriven/queenswood/Y/events.clj` — handlers
     for events this brick reacts to, arriving off another
     brick's changelog via the relay (ADR-0021). Requires no
     `fdb` — reactive work runs in its own transaction through
     `core/*`, never inside the changelog checkpoint. Gate the
     second leg on the loaded record's current status and skip
     silently when it doesn't match, so redelivery is a no-op.
   - `src/com/repldriven/queenswood/Y/system.clj` —
     `defcomponents :Y {:processor ... :event-handler ...}`.
     Inject `:record-db`, `:record-store`, `:schemas` as
     `system/required-component`.
   - `src/com/repldriven/queenswood/Y/validation.clj` —
     optional. Predicate-style validators called from
     `domain.clj` when domain validation gets multi-step.
   - `deps.edn` — copy from `cash-account`. Bricks are resolved
     by the Polylith workspace, so the file stays `:paths
     ["src"]` plus the `:test` alias; nothing to add per
     dependency.

3. **Pick the group and register the brick in its base.** Pick
   by boundary (financial: posts/settles/accrues/gates a
   payment; operational: provisions or verifies) per
   [ADR-0036](../../../docs/adr/0036-simulators-run-in-a-service-of-their-own.md),
   then add `com.repldriven.queenswood.Y.interface` to that
   group base's require bundle — the `system.clj` in
   `bases/financial-processors/` or
   `bases/operational-processors/`. There is no per-processor
   base — processors are hosted by group services.

4. **Wire the processor into the same group's project** —
   `projects/financial-processors-service/` or
   `projects/operational-processors-service/`:

   - `resources/system/Y.yml` — the domain's processor /
     command-processor / event-consumer system config.
   - `resources/application.yml` — message-bus producers and
     consumers for the new topics, and the
     `!include system/Y.yml` line under a top-level key.
   - `deps.edn` — add the brick (and any new transitive
     bricks) as `:local/root` paths; `poly check` confirms.

5. **Add Avro schemas** for the new commands and events under
   the schema brick, per
   [recipes/code/code-generation.md](../../../docs/recipes/code/code-generation.md).
   Run `clj -X:deps prep :aliases '[:dev]' :force true`
   to regenerate.

6. **Verify the invariants** below before considering the brick
   complete.

7. **Deployment plumbing** — none, in the common case: the
   group services' Helm/Tilt/CI entries already exist. Only a
   processor needing its own dedicated deployment (a boundary
   or scaling case per ADR-0036) adds chart entries, per
   [recipes/infra/deployment.md](../../../docs/recipes/infra/deployment.md).

## Invariants (verify on every change)

Run through this list after any scaffold, add-command, or
modify pass. The TDD has the rationale; the rule itself is
what matters here.

- [ ] `fdb/*` requires appear **only** in `store.clj`. No
      `core.clj`, `domain.clj`, `commands.clj`, `events.clj`,
      `interface.clj`, or `validation.clj` may require
      `com.repldriven.queenswood.fdb.interface`.
- [ ] No `watcher.clj`. The changelog relay replaced watchers
      (ADR-0021); reactive work arrives as an event and is
      handled in `events.clj`.
- [ ] `domain.clj` requires no `fdb`, no `store`, no
      `schema`. Inputs are plain data; outputs are plain
      data or `:rejection/anomaly`.
- [ ] All `:rejection/anomaly` originate in `domain.clj` (or
      `validation.clj` called from `domain.clj`). The store
      layer raises only `:error/anomaly` for infra faults.
- [ ] Every public fn in `interface.clj`, `core.clj`, and
      `store.clj` takes `txn` as its first parameter. The
      param is named `txn` (not `db`, `tx`, `transaction`).
- [ ] Every public `core.clj` fn that performs more than one
      store call wraps its body in
      `(store/transact txn (fn [txn] ...))`.
- [ ] Cross-brick reads/writes in `core.clj` pass the current
      `txn` through (e.g. `parties/get-party txn org-id
      party-id`), so the whole flow commits atomically.
- [ ] `domain.clj` does **not** receive `txn`. Effects it
      needs (counters, generated ids) are passed in as
      closures from `core.clj`.
- [ ] `store.clj` schema-translates at the boundary: domain
      data in, FDB record types out (and vice versa). No
      domain code touches `schema/X->java` or similar.
- [ ] `commands.clj`'s `dispatch` returns a
      `:rejection/anomaly` for unknown command names and an
      `:error/anomaly` for missing schemas — never throws.
- [ ] IDs and timestamps come from `utility` —
      `utility/uuidv7`, `utility/generate-id`, `utility/now`,
      `utility/now-rfc3339` — never `random-uuid`,
      `Instant/now`, or `System/currentTimeMillis`.
- [ ] No `use-fixtures` in tests. Test lifecycle via
      `with-test-system`; anomaly assertions via `nom-test>`.

## Adding a command to an existing processor

Shorter path for mode 2 — adding one new command to a brick
that already exists:

1. **Domain logic first.** Add the new fn to `domain.clj`
   working on pure data. Return the new entity or
   `error/reject` for any rejections.
2. **Storage if needed.** If the command introduces a new
   read or write, add a fn to `store.clj` wrapping
   `fdb/transact txn (fn [txn] ...)`. Reuse existing
   store-name and schema converters where possible.
3. **Orchestrator.** Add a public fn to `core.clj` that
   opens (or joins) a transaction via `store/transact`,
   reads what it needs (forwarding `txn` to every call),
   delegates to `domain/*` for the calc/decision, writes
   back via `store/*`.
4. **Public surface.** Expose the new fn from
   `interface.clj` with a docstring (args, return shape,
   anomaly cases).
5. **Command handler.** Register the command name → handler
   entry in the `command-handlers` map in `commands.clj`,
   wired to the new `core/*` fn. The handler takes
   `[config data]` and passes `config` as the `txn` arg —
   that's the top-of-stack `txn-or-config` shape.
6. **Avro schema.** Add the command's request/response
   schemas, then `clj -X:deps prep :aliases '[:dev]'
   :force true`.
7. **Invariants.** Re-check the list above.

## Canonical example

When in doubt about how a file should look, open the
matching file in `components/cash-account/` and copy
its shape. That brick is the standard model for the
pattern. `party` is a useful second reference for
domains that need uniqueness-violation handling.
