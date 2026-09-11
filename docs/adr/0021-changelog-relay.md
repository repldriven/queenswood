# 21. Changelog relay for reactive state transitions

<!-- tessl-plugin: design -->

## Status

Accepted. Supersedes [ADR-0008](0008-changelog-watchers.md).

## Context

ADR-0008 chose in-process changelog watchers, and named this path in
its "Future" section: give the watcher the narrow job of relaying to
the message bus, and move consumers to the broker. It expected to take
that path only if scale-out became a constraint. Scale-out is not what
forced it.

The premise ADR-0008 was reasoning from was also wrong in one detail.
FDB watches were never used — nothing calls `.watch()`. Every
"watcher" was a polling changelog consumer: a daemon thread calling
`changelog/process` every 100 ms. A sentinel key is bumped on every
commit and documented as "suitable for FDB watches", and nothing reads
it. So the choice was never watch-versus-poll; it was where the poll
loop lives and what it is allowed to do.

Four defects came out of letting a domain handler run in that loop:

- **Domain work ran inside the changelog checkpoint transaction.**
  `changelog/process` invokes `(handler ctx changelog-bytes)` inside
  `record-db.run`, so the handler shares the transaction that advances
  the cursor. `idv/watcher.clj` did a Kafka `message-bus/send` *and*
  opened its own nested FDB transactions from in there. FDB caps a
  transaction at 5 s and re-runs it on retry, so a slow broker made
  cursor progress fail, and a retry re-sent the message.
- **Errors were swallowed.** The watcher loop was
  `(catch Exception _)` with no logging. A permanently failing handler
  spun at 10 Hz forever, silently, with no dead-letter path.
- **Dedup dropped transitions.** `changelog/process` defaults to
  latest-entry-per-record-id, and the record-id is the entity id, so
  two transitions committed inside one poll window collapsed and the
  earlier one was lost.
- **One cursor per `consumer-id` pinned its whole service to
  `replicas: 1`.** A cursor has exactly one owner, so any service
  hosting a watcher could not be replicated at all — regardless of
  whether the domain work it did needed to be.

Each of these is a consequence of one thing: the component that owns
the cursor was also the component that owned the business logic.

## Decision

We separate the two jobs: the tier that owns a cursor holds no domain
logic, and the brick that holds domain logic owns no cursor.

The rules that follow from that split:

1. **The relay tier owns cursors and nothing else.** `changelog-relay`
   is a config-driven runner that tails one store's changelog and
   republishes each entry to the message bus. It holds no domain logic.
   It passes `{:deduplicate? false}`, because a relay carries
   transitions and collapsing two of them would drop an event.
   `exclusive-dispatchers-service` hosts every runner and runs at
   `replicas: 1`; the tier scales by sharding stores across
   deployments, not by adding replicas.
2. **Every store writes one envelope.** A store's `write-changelog`
   payload is a `ChangelogEvent`: `event_id` (uuidv7, the consumer's
   dedup key), `dedup_key`, `event_name`, `payload` (Avro),
   `correlation_id`, `causation_id`, `created_at`, `traceparent`,
   `ordering_key`. One handler kind decodes every store's changelog
   without knowing the domain, and republishes `payload` verbatim —
   the relay never deserialises it. Because the adapter outbox protos
   reuse `ChangelogEvent`'s field numbers and wire types, their
   entries decode as one too.

   Not every field crosses. The relay carries `event_name`, `payload`,
   `correlation_id`, `causation_id` and `traceparent` into mono's
   `EventEnvelope`, carries `event_id` as that envelope's `id` so a
   consumer dedups on the entry rather than on an id the publisher
   minted, and hands `ordering_key` to the bus as the publish key.
   `dedup_key` and `created_at` stop at the relay: `EventEnvelope` has
   no field for either, so a consumer needing the logical identity of
   a change, or the time it committed, reads it from the Avro payload.
   Carrying them is a mono change under
   [ADR-0001](0001-reuse-mono-as-upstream.md).
3. **Consumers are event processors in the reacting brick.** A brick
   that reacts to another's transition subscribes to an event channel
   and handles the event in its own `events.clj`, outside any changelog
   transaction, against its own records. The subscription is a
   per-brick `<brick>/event-processor` kind wrapped in mono's
   `event-processor/event-processor`, which leaves an event
   unacknowledged when the handler throws or returns an anomaly, so
   the bus redelivers it rather than dropping the transition.
4. **Cursor ids are physical and are never minted fresh.**
   `changelog/scan` range-reads everything after the checkpoint and
   calls `.asList` with no limit, inside one transaction. A new
   `consumer-id` starts at `nil` and would try to read a store's entire
   changelog history in a single transaction, and republish every
   historical transition. When a consumer moves, it carries its old id
   verbatim — which is why the `idvs` cursor is still called
   `parties-watcher`.
5. **Lifecycle guards are the idempotency gate.** Delivery is
   at-least-once: the cursor advances only when the whole pass commits,
   so a handler that throws leaves the checkpoint in place and the
   entry is redriven. The source-status guard in `domain.clj` is what
   makes that safe — a transition arriving twice finds the entity
   already past that state and skips silently, rather than rejecting.
6. **The HTTP layer stays ignorant of downstream effects.** When a
   flow needs N bricks to react to one request, the shape is N
   consumers — each in the reacting brick — not an HTTP-layer
   orchestrator chaining commands across bricks. A brick publishes its
   own changes and acts only on its own records; it never reaches into
   another's. The webhook component is the stated exception: a
   notification body must equal what a domain's read route returns,
   which only that domain can render, so it reads across domains
   through each catalogued domain's `*-query` brick.

Rule 6 is ADR-0008's brick-boundary rule, carried over with the one
exception stated in it, and is the reason this is a change of transport
rather than of architecture.
What changed is that the hop between "X committed" and "Y reacts" is
now a broker rather than a function call in the same JVM.

## Consequences

Easier:

- A handler can take as long as it needs, make network calls, and open
  its own transactions, because it no longer shares the transaction
  that advances a cursor.
- Failures are visible. The runner logs and redrives; consumers get a
  broker's dead-letter and redelivery machinery.
- Reactive flows are inspectable and replayable from the bus, and can
  fan out to consumers that were never designed for FDB.
- Services that react to events scale independently of the cursor. Only
  the relay tier is pinned to one owner per cursor.
- Traces survive the hop. The writer's `traceparent` travels in the
  envelope, so a consumer's span joins the trace that caused it rather
  than opening its own.

Harder:

- Two more wire formats on the reactive path: the envelope in the
  changelog and the Avro event payload inside it. A new reactive flow
  now needs an event schema registered, where a watcher needed nothing.
- A broker hop, and with it at-least-once delivery to reason about.
  Consumers must be idempotent — which they had to be before, but the
  failure mode is now redelivery rather than replay.
- The relay is a distinct tier to deploy, configure and keep at
  `replicas: 1`.
- Ordering is preserved in FDB and carried only as far as the writer
  declares. The relay passes `ordering_key` to the bus as the publish
  key, and an entry without one publishes unkeyed, so ordering holds
  today by every topic having one partition rather than by design. See
  "Future".

## Future

One thing gates real scale-out.

**Stores must declare `ordering_key`, before partition counts rise.**
Keying does not restore commit order across a topic; it makes global
order unnecessary. A broker orders within a partition only, so an
entity's own transitions stay in sequence as long as they share a key,
and nothing depends on one entity's order relative to another's. The
relay already hands the field to the bus, so what is missing is the
writer: a store that declares none publishes unkeyed, and raising
partition counts under that would silently break per-entity ordering.

Unkeyed reordering is invisible rather than loud, which is what makes
it worth fixing before it can happen. A `closing` event overtaking its
`opening` lands on a source-status guard that skips silently — by
design, so redelivery is a no-op. The guard that makes replay safe is
the guard that hides reordering.

Smaller, in `components/fdb`: `changelog/process` needs a `:limit`
batch cap, since with `{:deduplicate? false}` a burst issues N
publishes inside one transaction; and `read-checkpoint` should read
through the processing transaction, so the checkpoint key enters the
outer read-conflict set and FDB's own optimistic concurrency makes
multi-replica relays safe without a lease.

Leader election remains deliberately unbuilt. `replicas: 1` plus a
durable cursor already survives a crash, and the relay's work is
decode-and-publish — O(1) per entry, no business logic. Leader election
would shorten the gap before someone resumes; it buys failover, not
throughput.
