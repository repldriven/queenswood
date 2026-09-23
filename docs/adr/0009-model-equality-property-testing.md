# 9. Model-equality property testing

<!-- tessl-plugin: design -->

## Status

Accepted.

## Context

The bank has rich state-transition logic spread across many
components — balances, accounts, payments, transactions, policies,
interest, identity verification, lifecycle. Most of the bugs that
would actually hurt are bugs in *sequences* of operations rather
than in single calls: the third inbound transfer in a row that
crosses a limit, the apply-fee that drops a balance into curative
territory before the next outbound, the reverse-then-reopen that
leaves a stale projection.

Example-based tests find the cases we already thought of, which is
exactly the wrong shape for state-machine bugs. We need an
approach that explores sequences nobody wrote down and surfaces the
ones that diverge.

The shortlist:

- **Example-based tests only.** Easy to write; low coverage of edge
  cases; structurally blind to state-transition bugs. Kept for pure
  functions in isolation; rejected as the *primary* system-level
  mechanism.
- **Property-based testing with hand-rolled invariants.** Properties
  like "available balance never goes below floor for in-scope
  transactions". Rejected as the primary approach — banking bugs
  are predominantly state-transition bugs, not invariant violations.
  A comprehensive invariant set is hard to write and incomplete by
  construction.
- **Stateful PBT via fugato, stateful-check, fsm-test-check, or
  states.** All reasonable. We pick
  [fugato](https://github.com/vouch-opensource/fugato) for two
  reasons: symbolic-only command generation (cheap to produce long
  sequences without burning time on argument detail),
  and "bring your own runner" fit (we already need a runner for
  hand-authored scenarios, so reusing it is free).
- **Cucumber / Gherkin.** Rejected. The natural-language layer is
  mostly tax in an engineering team — regex step definitions,
  English ↔ code translation. EDN keeps the structure without the
  parser.
- **Snapshot testing.** Considered for complex outputs (a
  capitalisation run, whose per-account transactions and
  aggregate ledger entry have to be checked together). Not
  adopted upfront — snapshots become noise generators when
  they're too broad. May add later for very specific cases.
- **Per-component projection functions** (putting `projection.clj`
  inside `balance`, `cash-account`, and so on). Rejected
  — leaks test concerns into production paths, and cross-component
  property tests would have no natural home.

## Decision

We will use **model-equality property testing** as the primary
system-level testing approach.

The shape:

- A pure-functional reimplementation of the bank's domain rules —
  the **model** — runs in parallel with the real system. The model
  is small (a few hundred lines for the whole bank) and imports
  nothing from production: no FDB, no message bus, no protobuf, no Malli,
  no nom, no real IDs, not even `policy` (the model carries its
  own re-implementation of the policy rules it needs).
- Fugato generates command sequences from a model spec
  (`{:run? :args :next-state :valid? :freq}`).
- Both the model and the real system process the sequence.
- **Projection functions** reduce real-system state to the same
  shape as model state.
- The property is equality between model end-state and projected
  real-system end-state.
- When they diverge, fugato shrinks to a minimal reproducer.
- Hand-authored EDN scenarios share the same runner and
  projections — used for cases we want locked down explicitly.
- Three test-only components (carried by `project:dev` for the
  test runner; *not* part of any deployable project):
  - `test-model` — the pure model.
  - `test-projections` — projection fns built on production
    component *interfaces only*.
  - `test-scenarios` — command dispatch, ID side-table,
    quiescence wait, divergence debugging.
- The dependency arrow points test → production, never the reverse.
  Polylith enforces this: if a production component starts importing
  from `test-*`, the build complains.
- The architecture is documented in detail at
  [docs/tdd/scenario-testing.md](../tdd/scenario-testing.md);
  this ADR captures the decision and the rejected alternatives.

## Consequences

Easier:

- Bugs in state transitions across multiple components surface
  naturally without anyone writing the specific case. When fugato
  shrinks to a minimal reproducer, the diff between model and
  projected real-system state is the answer.
- The model doubles as executable documentation of *what the bank
  is supposed to do*, separate from how it does it. Reading the
  model is the cheapest way to understand the rules.
- Hand-authored EDN scenarios use the same runner and projections
  — no parallel test infrastructure.
- The test → production dep arrow is enforced by Polylith, so
  "don't leak test concerns into prod" doesn't rely on discipline.

Harder:

- **We are deliberately writing the rules twice.** The model is a
  parallel implementation of the same logic. This is the cost. The
  defence is that the two implementations live at very different
  levels (pure-functional small-state model vs full production
  system) and divergence is exactly what we want to find.
- The model can drift from production if not maintained. When
  production policies or rules change, the model's pure-function
  versions must change too. This is a discipline, not a guarantee.
- The CQRS read-side lags the write-side. The runner *must* wait
  for quiescence before projecting, or tests flake in ways that
  look like real bugs. This is the single largest source of false
  failures and is non-negotiable.
- Generation strategies need tuning. Fugato sequences too short
  miss bugs; too long shrink slowly. Frequency weights, sequence
  length, and shrink minimums are knobs that need attention as the
  suite matures.
- These tests are slower than pure-function tests — they boot real
  components, hit real FDB, real broker. They run alongside fast
  tests, not in place of them.

This is a Queenswood-specific decision. `mono` ships the
infrastructure primitives the runner stands on (system, fdb,
testcontainers, message-bus), but the model and projections are
necessarily domain-specific and live only in Queenswood.
