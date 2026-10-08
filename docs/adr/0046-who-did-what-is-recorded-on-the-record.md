# 46. Who did what is recorded on the record

<!-- tessl-plugin: design -->

## Status

**Proposed**

## Context

The property wanted is that every change a person or an operator makes
to a bank's records can be attributed: who created a record, who moved
it through each step of its life, and when. A regulator, an auditor or
a bank asking why customers' accounts moved onto new terms asks first
who approved it.

Without it, a record says when it changed and not who changed it. A
bank carried its creator, but as an optional field a legacy fallback
filled with an operator called `unknown`. A cash-account migration was
approved and cancelled with no record of by whom. A product version
moved to published with neither a time nor an actor. The access log
records who invited whom and who changed a member's role, which is
access, not an audit of what was done to the bank's data.

The shortlist:

- **An audit log beside the records, written by every operation.**
  Rejected: every write path has to remember to write it, nothing
  refuses a change that forgot, and the log and the record can
  disagree, since they are two writes that each path keeps in step by
  hand.
- **An audit log derived from the changelog alone.** Rejected as the
  whole answer: only stores with a changelog are covered, and its
  envelope carries no actor, so it records what changed and not who
  changed it.
- **The actor on the record, beside the time of each act.** The proto
  makes an act's actor required where one always exists, so a record
  cannot be written without it, the actor commits with the change it
  describes, and a reader finds it where it reads the record.

## Decision

Every act a person or an operator performs on a record is recorded on
that record as an `_at` and a `_by` pair, the `_by` an `Actor`, required
where an actor always exists and set by the domain from the caller the
API passes in.

The decision has these parts:

- Record a record's creation as `created_at` and `created_by`, and its
  last change as `updated_at` and, where it matters, `updated_by`, in
  the audit block at 101 to 104.
- Record each transition a person or an operator performs as a
  `<transition>_at` and `<transition>_by` pair in the record's
  transition band, 51 to 99.
- Record no actor for what the platform does by itself, as the
  scheduler completing a migration, until the platform has an actor of
  its own.
- Build the caller's actor in one place in the API, from the request's
  authentication, and pass it to the domain rather than having the
  domain look it up.
- Keep a record's history, where it needs one, on its store's
  changelog, and carry the actor on the changelog envelope when that
  history is built.
- Show who did what through a view per kind of record in the console,
  read from the records themselves.

## Consequences

Easier:

- A record cannot be saved without the actor its proto requires, so a
  write path that forgets one fails rather than writes an unattributed
  change.
- The actor and the change commit together, so nothing can drift
  between them.
- Reading who did something is reading the record, with no second store
  to join.

Harder:

- A record holds the latest of each act, not every one. An edit that
  happens many times keeps only its last editor, and the history of
  edits exists only where a changelog does and only once its envelope
  carries the actor.
- Every transition added to a record needs its `_by` threaded from the
  API through the domain, and a test that drives the domain directly
  has to supply an actor.
- Coverage drifts as records are added: a record whose transitions
  carry no `_by` is visible by reading its proto against
  [record-protos](../recipes/code/record-protos.md), and nothing yet
  checks it mechanically.
- The console's audit view and the actor on the changelog envelope are
  still to build, so a claim that the platform is auditable waits for
  both.
