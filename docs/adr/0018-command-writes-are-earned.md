# 18. Command writes are earned, not default

<!-- tessl-plugin: design -->

## Status

Accepted.

## Context

Queenswood moves state through two write shapes. Command writes go
over the bus: an API handler (or webhook/adapter) sends a command
envelope, a processor consumes it, performs the whole operation in
one FDB transaction, and replies. Synchronous writes call a
component interface directly from the handler that holds the FDB
handles.

The CQRS rollout — [ADR-0017](0017-query-write-brick-split.md) —
commandified domain after domain — cash-account, party, payment,
cash-account-product, transaction, interest, idv, payee-check, and
now bank — and the natural question arrived: does the pattern run
to its logical conclusion? Should jobs, policies, memberships, and
the auth-interceptor user upsert become commands too, for
consistency?

Consistency of mechanism is not the goal; consistency of the
*decision rule* is. A command path is not free: measured on the
create-bank commandification, one command costs roughly 25 touched
files — Avro schemas registered in two registries, a commands/system
pair on the brick, a processor base, a service project with message-bus
config, workspace/Tilt/Helm entries, monolith and api wiring,
and the test system in three files — plus a bus round-trip of
latency on every call and a reply-timeout failure mode the
synchronous path does not have.

The label "financial pathway" turned out to be a proxy. idv
and payee-check are commandified and move no money; they
graduated because of how they write, not what they are about.

## Decision

A write becomes a command when it has at least one of four intrinsic
properties. A write with none of these stays synchronous: a direct FDB
write is already atomic for standalone configuration, and the bus adds
only latency and ceremony.

The properties, today's classification, and two corollaries:

- **The properties:**
  1. **Multi-record atomicity under contention.** The write spans
     records or bricks and must commit or abort as one — create-bank
     allocates a sort code and writes the bank, its org party, its
     ledger chart, house accounts, policy bindings, and the owner
     membership in a single transaction.
  2. **Idempotency stakes.** A redelivered or double-submitted write
     causes real-world damage (two payments, two banks), so it needs
     the command envelope, serialized consumption, and a store-level
     idempotency check.
  3. **Reaction.** Other bricks must respond asynchronously to the
     write via the changelog, per
     [ADR-0021](0021-changelog-relay.md) — payment to
     transaction to balance; an IDV result activating a party.
  4. **Unreliable ingress.** The write originates from a webhook or
     external event and needs consume-then-ack semantics.
- **Applied to today's paths:**
  - **Commands**: cash-account, party, payment, transaction, interest,
    idv, payee-check, bank.
  - **Synchronous**: cash-account-product (each write touches one
    product record, nothing reacts to any of them, and a retry is read
    back off the idempotency-key index — commandified during the
    rollout, and moved back once the rule was applied to it rather
    than assumed), scheduler/jobs (single-record config writes; an
    admin UI wants read-your-writes), policy (no write API — writes
    happen at bootstrap seeding and inside bank creation's
    transaction), membership (never an independent write; it lives
    inside the create-bank transaction), user upsert (auth-interceptor
    hot path — must stay cheap and synchronous, permanently).
- **The classification is a consequence of the rule, not the rule.**
  Graduation triggers are known in advance: a policy-authoring API
  whose changes require downstream reaction (limit re-evaluation,
  audit events) graduates policy; invitation flows (asynchronous,
  multi-party) graduate membership. When a path crosses a criterion,
  it moves; nothing moves for symmetry.
- **Query splits follow readers, not symmetry.** A commandified
  brick gains a `-query` sibling (ADR-0017) when a reader outside
  its processor needs its reads — not before. transaction,
  interest, idv, and payee-check stay unsplit until such a reader
  appears.
- **No event sourcing.** The changelog-as-outbox remains the event
  backbone; FDB records stay the source of truth. Events exist to
  cross boundaries (external adapters, the message bus), not to
  rebuild state.

## Consequences

Easier:

- Scope decisions stop being debates. A new write path is
  classified by four checkable properties, and the PR that adds it
  can cite which one it crossed.
- The synchronous paths stay cheap: no bus hop for admin
  configuration, no Avro/wiring tax for writes that gain nothing
  from an envelope.
- ADR-0017's opt-in enforcement design is now explicit policy: the
  guardrail keys off a `-query` sibling's existence precisely
  because not every domain earns the split.

Harder:

- The boundary needs judgment at the margins, and drift is
  possible: a synchronous path can quietly acquire reaction or
  idempotency stakes without anyone re-classifying it. Reviews of
  new writes should ask which side of the rule they fall on.
- Mixed mechanisms mean two write idioms coexist in api
  (dispatch a command vs call an interface), and readers of a
  handler must know which they are looking at.
