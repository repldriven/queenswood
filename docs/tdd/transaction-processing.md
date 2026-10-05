# Transaction processing

## Objective

Queenswood is fundamentally an OLTP system — online transaction
processing. A user request must become an atomically-committed
transaction with a clear outcome reported back to the caller.
Other parts of the system must be able to react to facts emitted
by committed transactions.

This TDD describes how Queenswood implements transaction
processing — the request-response round-trip from HTTP through
processors, the downstream event fan-out from committed writes,
and the status semantics that thread these together.

In scope: the `command`, `command-processor`, `event`, and
`event-processor` bricks; envelope shape; status semantics;
correlation; reply round-trip; and the transactional guarantees
that separate work inside FDB from work that crosses a boundary.

Out of scope: the message-bus abstraction per
[ADR-0003](../adr/0003-message-bus-abstraction.md), Avro
encoding per
[ADR-0004](../adr/0004-avro-for-message-payloads.md), and
FDB changelog and relay mechanics per
[ADR-0021](../adr/0021-changelog-relay.md).

## Background

Banking is the prototypical OLTP use case. A request to open an
account, transfer money, or apply a fee must commit atomically
or refuse cleanly, report its outcome back to the caller in a
timely way, and surface its facts so other parts of the system
can react.

The synchronous-feeling shape (caller waits for outcome) is
load-bearing. Banking APIs are expected to return "your transfer
was accepted" or "your transfer was rejected because ..." — not
"we'll get back to you." Response timing matters; an end user
is looking at a spinner.

Queenswood implements OLTP with three flows on a shared message
bus:

- **Commands** — imperative requests that one processor handles
  ("open this account", "settle this payment").
- **Replies** — structured responses returned to the caller via
  the same bus.
- **Events** — facts emitted from committed writes that any
  number of subscribers can react to ("transaction settled").

The shared substrate is the message-bus abstraction per
[ADR-0003](../adr/0003-message-bus-abstraction.md) with Avro
payloads per
[ADR-0004](../adr/0004-avro-for-message-payloads.md).
Anomalies at component boundaries per
[ADR-0005](../adr/0005-error-handling-with-anomalies.md)
provide the typed-failure semantics that map directly to
envelope statuses.

## Proposed Solution

### Architecture

Five bricks make up the transaction-processing pipeline:

- **`command`** — provides the wire envelope, the dispatcher
  (caller side), and the processor harness (handler side). Used
  by HTTP handlers and processors alike.
- **`command-processor`** — system-component registrations
  binding named processors (`cash-account/processor`,
  `payment/processor`, etc.) to the bus.
- **`event`** — provides the event envelope, the publisher,
  and the consumer harness.
- **`event-processor`** — system-component registrations for
  named event subscribers.
- **`processor`** — small `Processor` protocol that
  domain-specific processors implement.

```mermaid
graph LR
    HTTP["HTTP API<br/>handler"]
    BUS[("message-bus")]
    PROC["Processor<br/>(e.g. cash-account)"]
    FDB[("FDB")]
    SUB["Event subscriber<br/>(e.g. payment settle)"]

    HTTP -->|"command envelope"| BUS
    BUS -->|consume| PROC
    PROC -->|commit| FDB
    PROC -->|"reply envelope"| BUS
    BUS -->|"reply matched"| HTTP
    PROC -->|"event envelope"| BUS
    BUS -->|"fan-out"| SUB
```

### Data model

**Command envelope** (request from HTTP to processor):

```clojure
{:command         "<command-name>"
 :id              "<idempotency-key>"
 :correlation-id  "<trace-id>"
 :command-id      "<per-send-uuid>"  ; stamped by the dispatcher
 :causation-id    nil
 :traceparent     "<otel>"
 :tracestate      nil
 :payload         {...}             ; command-specific
 :reply-to        nil}              ; reply topic address, unused
```

`:id` is the caller-supplied idempotency key.
`:correlation-id` threads through the whole chain and defaults
to `:id` if no header was supplied. `:command-id` is minted fresh
by the dispatcher on every send and identifies this one attempt —
it is the reply-matching key (see below), distinct from `:id` and
`:correlation-id`, both of which a retry reuses. `:causation-id`
links a downstream message to its predecessor. `:reply-to` is the
reply topic address — designed to name a destination, but there is
one shared response channel today, so it stays unused.

**Response envelope** (reply from processor to caller):

```clojure
{:id              "<fresh-uuidv7>"
 :correlation-id  "<from-request>"
 :command-id      "<from-request>"   ; echoed — the reply-matching key
 :causation-id    "<request :id>"
 :traceparent     "<otel>"
 :status          "ACCEPTED" | "REJECTED" | "FAILED"
 :payload         {...}             ; on ACCEPTED
 :reason          "<anomaly-kind>"  ; on REJECTED / FAILED
 :message         "..."}            ; on REJECTED / FAILED
```

**Event envelope** (fact from processor to subscribers):

```clojure
{:id              "<fresh-uuidv7>"
 :event           "<event-name>"
 :correlation-id  "<originating-correlation>"
 :causation-id    "<commit-id-or-prior>"
 :traceparent     "<otel>"
 :payload         {...}}            ; event-specific
```

**Status mapping** — three outcomes from process-fn map cleanly
to envelope statuses and HTTP families:

- Non-anomaly value → `ACCEPTED` → 2xx
- `:rejection/anomaly` → `REJECTED` → 4xx
- `:error/anomaly` → `FAILED` → 5xx

`:unauthorized/anomaly` does not reach the pipeline — the auth
interceptor short-circuits the HTTP request before the command
is dispatched.

### Reading the diagrams

The diagrams follow one command, opening a cash account, through every
stage of the pipeline, and are read as
[payments-internal.md](payments-internal.md#reading-the-diagrams) sets
out. The cash-account topics have one partition and the open route sends
no ordering key, so their participants name no partition key where the
relay's do.

### Flows

#### The API sends the command and waits for its reply

```mermaid
sequenceDiagram
    box rgba(233, 236, 239, 0.5)
    participant C as Client
    end
    box rgba(165, 216, 255, 0.45)
    participant API as api-service<br/>POST /v1/cash-accounts
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant CC as topic-cash-accounts-command<br/>one partition, unkeyed
    participant CR as topic-cash-accounts-command-response
    end
    C->>API: open, Idempotency-Key
    critical transact
    API->>DB: read the idempotency entry for the key
    opt no live entry
    API->>DB: save it, pending
    end
    end
    alt completed before
    API-->>C: the response recorded, Idempotent-Replayed
    else pending, another request with the key in flight
    API-->>C: 409
    else the key used before with another body
    API-->>C: 422
    else claimed
    API->>CC: open-cash-account, a command-id minted for this send
    CR->>API: the reply carrying that command-id, within timeout-ms
    alt 2xx or 4xx
    critical transact
    API->>DB: save the entry, completed, with the response
    end
    else 5xx, or no reply in time
    critical transact
    API->>DB: delete the entry
    end
    end
    API-->>C: 201 ACCEPTED, 4xx REJECTED, 5xx FAILED or unknown
    end
```

The dispatcher matches a reply to its waiting request by the command-id
it minted for the send, so a reply to an earlier attempt satisfies no
later one. `ACCEPTED` answers 201 with the account, `REJECTED` the 4xx
its reason maps to, and `FAILED` or no reply within the dispatcher's
`timeout-ms` a 500, after which the client retries with the key.

#### The cash-account processor opens the account and replies

```mermaid
sequenceDiagram
    box rgba(165, 216, 255, 0.45)
    participant CC as topic-cash-accounts-command<br/>one partition, unkeyed
    end
    box rgba(208, 191, 255, 0.45)
    participant CP as operational-processors-service<br/>cash-account/processor
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant CR as topic-cash-accounts-command-response
    end
    CC->>CP: open-cash-account, one at a time
    critical transact
    CP->>DB: read the platform policies
    CP->>DB: read the bank's policy bindings
    loop each binding
    CP->>DB: read the policy it binds
    end
    CP->>DB: read the Party
    CP->>DB: read the product's versions
    CP->>DB: read the count of the bank's accounts
    CP->>DB: read the count by product type, account type and currency
    CP->>DB: read the platform policies, for the balances
    loop each balance the product declares
    CP->>DB: read the Balance for the bucket
    CP->>DB: save the Balance
    end
    CP->>DB: save CashAccount
    CP->>DB: write cash-account-status-changed to the cash-accounts changelog
    CP->>DB: write account-opening to the bank's activity log
    alt the idempotency key is new
    Note over CP,DB: the transaction commits
    else the key is recorded, a redelivery or a retry
    Note over CP,DB: the unique index on the key refuses the save,<br/>and the transaction aborts
    end
    end
    opt the transaction aborted on the key
    critical transact
    CP->>DB: read the CashAccount by its idempotency key
    end
    end
    alt an account
    CP->>CR: ACCEPTED, the account
    else a rejection
    CP->>CR: REJECTED, its kind and message
    else a failure, or a throw the harness caught
    CP->>CR: FAILED, its kind and message
    end
    CP-->>CC: ack
```

The processor replies, then acknowledges, in every case: a rejection
or a failure is an answer, and the harness turns a throw from the
process function into `FAILED` rather than letting it reach the consumer.
Only a crash before the ack redelivers the command, and the redelivery
meets the unique index on its key and answers with the account the
first delivery opened.

#### The change is relayed to the bus

```mermaid
sequenceDiagram
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant RR as exclusive-dispatchers-service<br/>changelog-relay/runners
    participant CE as topic-cash-accounts-event<br/>partition-key = account
    end
    critical transact
    RR->>DB: read the cursor, cash-account-watcher
    end
    critical transact
    RR->>DB: read a batch of cash-accounts changelog entries after it, at snapshot
    loop each entry, in commit order
    RR->>CE: the entry's payload, verbatim, once the bus has taken it
    end
    RR->>DB: write the cursor, the last entry read
    end
```

A pass publishes a batch, at most the 500 entries `fdb/process-changelog`
reads by default, and moves the cursor once, after the last: a publish
that fails aborts the pass, and the next pass publishes the batch again.
The relay publishes each entry under its ordering key, the account.

#### Each subscriber takes its own copy

```mermaid
sequenceDiagram
    box rgba(165, 216, 255, 0.45)
    participant CE as topic-cash-accounts-event<br/>partition-key = account
    end
    box rgba(208, 191, 255, 0.45)
    participant CS as operational-processors-service<br/>cash-account/event-processor
    end
    box rgba(255, 216, 168, 0.5)
    participant WS as external-adapters-service<br/>webhook/event-processor
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    par group cash-account-service-cash-accounts-event
    CE->>CS: cash-account-status-changed
    opt the change is to closing
    critical transact
    CS->>DB: read the CashAccount
    end
    opt still closing, and no provider holds it
    critical transact
    CS->>DB: read the CashAccount
    opt still closing
    CS->>DB: save it, closed
    CS->>DB: write cash-account-status-changed to the cash-accounts changelog
    end
    end
    end
    end
    CS-->>CE: ack
    and group webhook-service-cash-accounts-event
    CE->>WS: cash-account-status-changed
    opt the webhook catalogue names the change
    critical transact
    WS->>DB: read the CashAccount
    WS->>DB: read the bank's endpoints enabled for the kind
    WS->>DB: save the WebhookNotification
    loop each endpoint
    WS->>DB: save a WebhookDelivery, pending
    end
    alt the changelog event is new
    Note over WS,DB: the transaction commits
    else the event was taken before
    Note over WS,DB: the unique index on the event id refuses the save,<br/>the transaction aborts, and the event is taken as done
    end
    end
    end
    WS-->>CE: ack
    end
```

Each consumer group takes every event on the topic. A handler that
throws or returns an anomaly leaves its event unacknowledged, and the
consumer seeks back and delivers it again, up to the consumer's
`max-redeliveries`, so each handler is written to take an event twice.
A handler may write a changelog entry of its own, which its relay
publishes in turn, continuing the causation chain.

#### The account is opened at Modulr

The activity log entry reaches the bank's payment provider as
[payments-internal.md](payments-internal.md#the-activity-log-reaches-the-activity-processor)
draws for a transfer: its relay publishes it, and
`payment/activity-event-processor` sends `open-payment-account` to the
provider's command channel. On Modulr:

```mermaid
sequenceDiagram
    box rgba(165, 216, 255, 0.45)
    participant MC as topic-modulr-command<br/>partition-key = bank
    end
    box rgba(255, 216, 168, 0.5)
    participant MA as external-adapters-service<br/>modulr-adapter/command-processor
    participant IP as external-adapters-service<br/>modulr-relay/outbound-runner
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(233, 236, 239, 0.5)
    participant M as Modulr
    end
    box rgba(165, 216, 255, 0.45)
    participant SR as topic-schemes-command-response
    end
    MC->>MA: open-payment-account
    critical transact
    MA->>DB: save ModulrOutboundIntent open-account, pending
    alt the dedup key is new
    Note over MA,DB: the transaction commits
    else the intent is recorded, a redelivery
    Note over MA,DB: the unique index on the dedup key refuses the save,<br/>and the intent is taken as recorded
    end
    end
    MA->>SR: ACCEPTED
    MA-->>MC: ack
    critical transact
    IP->>DB: read every pending intent
    end
    critical transact
    IP->>DB: read every sent intent
    end
    critical transact
    IP->>DB: read the adapter's breaker, claiming the probe when half-open
    end
    loop each due pending intent no earlier unsent one shares an account with
    IP->>M: POST an account for the customer
    opt the call failed, or the breaker has counted a failure
    critical transact
    IP->>DB: read the breaker
    IP->>DB: save the breaker, with the call's outcome
    end
    end
    alt answered
    critical transact
    IP->>DB: read the intent
    IP->>DB: save the intent, settled
    IP->>DB: read the outbox for the event's dedup key
    IP->>DB: save ModulrOutboxEvent payment-account-opened
    IP->>DB: write it to the modulr-outbox changelog
    IP->>DB: read the opening intent by its dedup key
    IP->>DB: save it, with the provider account
    end
    else failed, to be tried again
    critical transact
    IP->>DB: read the intent
    IP->>DB: save it, with its attempts and next attempt
    end
    end
    end
```

The command's handler writes the call down rather than making it: the
intent commits, the reply goes to a topic nothing reads, and the
command is acknowledged. The poller makes the call outside any FDB
transaction and records its outcome, and the outbox event it writes in
the same transaction is relayed onto `topic-schemes-account-event`,
where `cash-account/payment-account-event-processor` marks the account
opened with the address Modulr issued.

### Transactional guarantees: inside FDB, across the boundary

One rule runs through the pipeline: everything that fits in a single FDB
transaction stays synchronous and atomic, and everything that crosses a
process or service boundary is at-least-once, made effectively-once by
de-duplicating on a key. The two halves need different handling, and
conflating them is where correctness bugs hide.

**Inside the FDB ecosystem — commit, then ack.** FDB gives multi-record
ACID in one transaction, so a processor does all its reads and writes,
across as many records and bricks as the operation touches, in a single
transaction that commits or refuses as a unit. An account's balances,
the account, its changelog entry and its activity entry commit together
or not at all. Nothing here goes through the bus: intra-FDB work is a
call inside `fdb/transact`, not a message. The one ordering rule for a
consumer is commit before ack — the processor commits its transaction,
replies, and only then is the bus message acknowledged, as
[The cash-account processor opens the account and replies](#the-cash-account-processor-opens-the-account-and-replies)
draws. Ack-before-commit loses the command on a crash;
commit-then-crash-before-ack redelivers it, and idempotency makes the
reprocess safe.

**As consumer across a boundary — idempotent consume-then-ack.** Anything
arriving over the bus (a command, a settlement event, a webhook-derived
event) is at-least-once: the broker redelivers on failure, and a crash
between commit and ack reprocesses. The consumer absorbs that by making
the effect idempotent on a key, in the same transaction: a unique index
on the key refuses the second write, so a second delivery aborts rather
than doubling the effect, and the consumer reads back what the first
made, as the cash-account processor and the webhook consumer above do.
This is not an outbox: the outbox is for producing, not consuming.

**As producer across a boundary — outbox and intent, then relay.** A
message a processor must emit as a result of a committed change (a domain
event, a next command, or an external HTTP call) cannot be sent inside
the FDB transaction without risking divergence: send-then-crash-before-
commit tells the world about a change that never happened;
commit-then-crash-before-send hides one that did. So write what to emit
into FDB in the same transaction as the state change, and relay it
separately. Two shapes, by what is emitted:

- A bus event becomes an outbox entry. Write the event to FDB atomically
  with the state change. FDB's changelog is the outbox, per
  [ADR-0021](../adr/0021-changelog-relay.md) — a relay runner reads
  the cursor, publishes to the bus, and advances the cursor,
  at-least-once, because the cursor only advances after a successful
  publish, as
  [The change is relayed to the bus](#the-change-is-relayed-to-the-bus)
  draws. A failed publish leaves the cursor unmoved and the entry is
  redriven; the consumer de-duplicates.
- An external call becomes an intent. Write the pending call to FDB as
  an intent record, then ack the trigger. A poller makes the call outside
  any FDB transaction — an HTTP round-trip cannot run inside one (FDB
  caps a transaction at five seconds, and a transaction retry would
  re-issue the call), so the poller reads the intent, calls out, and
  records the outcome in a separate transaction, as
  [The account is opened at Modulr](#the-account-is-opened-at-modulr)
  draws. The external service de-duplicates on its own idempotency field,
  so a retried call is safe.

The dedup key travels with each emitted message — the outbox entry's own
id, not only the originating command's key — so one command can produce
several distinct events without colliding at the consumer.

**Effectively-once, not exactly-once.** There is no exactly-once across
the bus boundary — that is the guarantee a durable log gives you, and
reaching for synchronous cross-service calls to fake it reintroduces the
timeout problem the pipeline exists to avoid. Every consumer
de-duplicates on a key, which makes the end-to-end behaviour
effectively-once: a message may be delivered, or a relay may publish,
more than once, but the effect lands once. The external adapters apply
exactly this — ingress-idempotent consume-then-ack, egress via an outbox
for events and an intent for the outbound HTTP call. See
[payments.md](payments.md) for the concrete adapter flows.

### Detailed design

**Dispatcher and reply matching.** `command/send` on the caller
side mints a fresh `:command-id` for the send, stamps it on the
envelope, and keeps a registry of in-flight requests keyed by that
`:command-id`. The processor echoes the `:command-id` back on the
reply; when a reply arrives on the shared reply channel, the
dispatcher resolves it against the registry by `:command-id` and
returns the response (or anomaly) to the caller. It waits for the
send's own `:timeout-ms`, else the dispatcher's `command-timeouts-ms`
entry for the command, else its `timeout-ms`, which every dispatcher
here takes from `system/command-timeout-ms.yml`, 10 seconds; an
expired request returns a `:command/timeout` anomaly. Keying
on the per-send `:command-id` — rather than `:correlation-id`,
which a retry reuses — is what keeps a straggling reply from one
attempt from satisfying another attempt's waiter. The reply
travels the one shared response channel regardless; `:command-id`
demultiplexes it in process, so no per-attempt reply *topic* is
needed.

**Processor harness.** `command/process` runs a consume-loop on
the bus, handing each envelope to the supplied process-fn. The
fn returns either a result map with a `:payload`, or an anomaly.
The harness wraps the outcome in `command/command-response` and
publishes the reply.

**Idempotency.** The caller-supplied `:id` (idempotency-key
header) rides every command. Dedup has two layers. At the HTTP
edge, the idempotency cache — keyed by principal, operation, and
client key — replays the original response on a client retry;
this is the exact-replay guarantee for HTTP-level retries. Below
it, processors that carry a unique idempotency-key index
(payments, transactions, cash-accounts) deduplicate a *bus
redelivery* at the store: the second write violates the index.
All three read the existing record back on that violation, so a
redelivery returns the original resource as `ACCEPTED` rather
than a rejection. A timeout is deliberately not cached (it maps
to a 5xx, which the cache skips so the caller can retry), so the
store-level index is what keeps that retry safe. Command families
without such an index inherit only the HTTP-edge layer.

**Correlation and causation.** `:correlation-id` is set once at
the HTTP edge and threaded through every command, reply, and
event in the resulting tree. `:causation-id` chains
parent → child: a reply's `:causation-id` is the command's
`:id`; an event's `:causation-id` is the commit reference; a
follow-up command's `:causation-id` is the event that triggered
it. Tracing across the bus follows the causation chain;
correlating a user action to its full effect tree follows
correlation-id.

**OpenTelemetry propagation.** `:traceparent` and `:tracestate`
travel on every envelope so traces span the bus boundary.
Headers are populated by `telemetry/inject-traceparent` at
envelope assembly.

**Avro on the wire.** Both command and event envelopes are
Avro-encoded for transport. Schemas live in `schema`
alongside the proto record schemas. See ADR-0004 for the
rationale and the brick organisation.

**Delivery guarantees and failure modes.** The synchronous
caller experience is a facade over an at-least-once bus, so a
reply timeout means "no reply within the window", not "not
executed" — the command may have committed while its reply was
lost. Callers treat a timeout as retryable and retry with the
same idempotency key; the store-level dedup above makes that
safe.

Processing is at-least-once: the consumer acks only after the
process-fn returns and the reply is published, so a crash before
the ack redelivers the command. Three things bound the failure
envelope:

- The consume loop wraps the handler. An unexpected throw is
  caught and negative-acked for redelivery instead of killing the
  loop (which would silently wedge the whole channel), and the
  process-fn is itself wrapped so a thrown exception becomes a
  `FAILED` reply — the caller always gets a response.
- A 30 s `ackTimeout` on every consumer redelivers a message that
  was delivered but never acked (a stuck handler, a dropped loop)
  even while the consumer stays connected — the broker's default
  leaves such a message undelivered indefinitely.
- A `deadLetterPolicy` (maxRedeliverCount 5, per-channel
  `*-command-dlq` topic) moves a genuinely poison command aside
  rather than redelivering it forever. Routing a message to the
  DLQ is silent until something watches the DLQ topic — alerting
  on it is an operational task, not a code one.

A reply can still be lost — a reply publish that fails, or one
that arrives after the caller's timeout — but the command ran, so
the idempotent retry path recovers it.

**Late replies are discarded, not cached.** The dispatcher's
in-flight registry is keyed by `command-id` and holds only the
callers currently waiting; `send` removes its entry the moment it
returns, whether a reply arrived or the timeout fired. So a reply
that lands after the timeout matches no waiter and is dropped —
nothing stashes it for a later request. Recovery is the idempotent
retry, not a replayed reply: the HTTP idempotency cache
deliberately does not cache a timeout (it is a 5xx), so the retry
re-runs the command and the store-level dedup makes that
re-execution safe.

Because `command-id` is minted per send, a retry — which reuses
the idempotency key and the correlation id — waits on a fresh key
of its own. A straggling reply from the first attempt carries that
attempt's `command-id`, so it can only ever match that attempt's
already-departed waiter and is dropped; it can never satisfy the
retry, which resolves to its own reply. This is independent of the
read-back above, and the two compose: the retry gets a
deterministic reply, and because payments, transactions, and
cash-accounts read the original resource back on a de-duplicated
retry (see Idempotency above), that reply carries a 200 with the
original resource rather than a rejection.

**Local channel bus.** The single-pod deployment replaces the broker
with an in-process core.async bus. It is at-most-once — no ack,
no redelivery, lost on crash — so durability there rests on FDB
plus idempotent retry, not the bus. The consume loop is still
throw-safe (a handler exception is logged, not fatal).

## Alternatives Considered

- **Synchronous RPC end-to-end (e.g. gRPC).** Keeps the sync
  feel without a message bus. Rejected because every call site
  would need explicit retry / timeout / circuit-breaker logic;
  downstream fan-out (the event side) needs separate plumbing;
  testing needs a mock service mesh. The bus-based approach
  gives all of this for free at the cost of a sync-over-async
  dispatcher.
- **Event-only architecture (no commands).** Every state change
  is an event; processors react, no request/response. Rejected
  because banking APIs need synchronous outcomes — callers
  expect "your transfer was accepted/rejected", not "we'll get
  back to you." Event-only systems work for some domains but
  not OLTP.
- **Saga orchestration.** A central orchestrator coordinates
  multi-step transactions across processors. Rejected for
  current scope: multi-record atomicity at the FDB layer covers
  the cases that would otherwise need sagas (a transfer touches
  sender balance, receiver balance, both posting legs, and a
  transaction record in one FDB transaction). Sagas would be
  needed if we ever spanned transaction boundaries (e.g.
  cross-bank transfers with external compensation), but we
  don't yet.
- **Direct HTTP → database.** No bus, no processors;
  controllers write directly. Rejected because: it ties HTTP
  thread-pool capacity to write throughput; replay/debugging
  needs ad-hoc instrumentation; downstream fan-out has no
  natural home; horizontal scaling of writes means scaling the
  API too.

## Known Limitations

- **One reply timeout in practice.** Every dispatcher waits
  10 seconds, and none names a command in `command-timeouts-ms`;
  the Form3 adapter's admission passes its own 4 seconds. A
  command that outlasts its wait has its caller answer 5xx;
  [idempotency.md](idempotency.md) says what a retry under the
  same key meets on each route.
- **In-flight commands during processor restart.** A command on
  the bus that has been delivered but not yet processed when
  the processor restarts depends on the bus backend's
  redelivery semantics. The broker backend acks on success and
  redelivers otherwise (bounded by `ackTimeout` and the DLQ);
  the channel-based backend is at-most-once and loses it. Test-
  and prod-shape behaviour can diverge here; covered by scenario
  testing — see
  [ADR-0009](../adr/0009-model-equality-property-testing.md).
- **Authorisation is not pipeline-aware.** The auth interceptor
  short-circuits before commands are dispatched; the pipeline
  treats every received command as already-authorised. If a
  future requirement needed per-command auth (rather than
  per-route), this would need to extend the envelope.
- **No multi-step saga coordination.** Per the alternative
  above — if cross-processor atomicity is ever required, this
  becomes a gap.
- **Store-level idempotency is per-processor and opt-in.** The
  pipeline carries `:id`, and the HTTP-edge cache covers client
  retries, but bus-redelivery dedup depends on each processor
  wiring a unique idempotency-key index. Payments, transactions,
  and cash-accounts do; a processor that adds a write path
  without one reopens the double-effect class. Confirmation of
  Payee, for example, has no such index — a redelivered CoP
  command records a duplicate check, accepted because it carries
  no financial effect and the records are short-lived.

## References

- [ADR-0003](../adr/0003-message-bus-abstraction.md) —
  Message-bus abstraction
- [ADR-0004](../adr/0004-avro-for-message-payloads.md) —
  Avro for message payloads
- [ADR-0005](../adr/0005-error-handling-with-anomalies.md) —
  Error handling with anomalies
- [ADR-0021](../adr/0021-changelog-relay.md) —
  the changelog relay for reactive state transitions
- [ADR-0009](../adr/0009-model-equality-property-testing.md) —
  Model-equality property testing
- [error-handling.md](../recipes/code/error-handling.md)
- [system-components.md](../recipes/code/system-components.md)
- `command` brick interface
- `command-processor` brick interface
- `event` brick interface
- `event-processor` brick interface
- `processor` brick interface
