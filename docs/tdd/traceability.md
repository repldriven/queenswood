# Traceability

## Objective

A single user action against Queenswood typically fans out
across many components — an HTTP handler, a command on the bus,
a processor that commits a transaction, events emitted from the
commit, downstream subscribers that react. When something goes
wrong (or just takes too long), we need to follow that action
end-to-end: which command went where, what reacted to it, where
time was spent.

This TDD describes the traceability machinery: the set of IDs
that thread through the system, how they propagate across the
HTTP boundary and the message bus, the OpenTelemetry stack
sitting underneath, and the gaps in coverage we have today.

In scope: the `telemetry` brick, OpenTelemetry tracing, the
ID set (correlation-id, causation-id, idempotency `:id`,
traceparent, tracestate), and how they propagate.

Out of scope: the log brick's plain-text logging mechanics
(`log/info`, `log/error`); idempotency semantics (separate
recipe, planned); error surfacing (covered by error-handling
recipe and ADR-0005).

## Background

Traceability in a CQRS-with-events system has more moving parts
than a single-process app:

- A user action triggers a command, which lives briefly on a
  bus before a processor consumes it.
- Processors may emit events, which fan out to multiple
  subscribers.
- Subscribers may emit further commands or events.
- All of this happens across process boundaries and possibly
  across deployments.

A trace tool like Jaeger or Tempo needs a way to see this whole
tree as one trace. The industry standard for that is W3C Trace
Context (`traceparent` / `tracestate` headers / fields), which
OpenTelemetry implements.

W3C trace context alone isn't enough, though. OTEL trace IDs are
128-bit hex blobs designed for trace tools, not human-readable
correlation. We also want:

- A **human-readable identifier** for a user action that's easy
  to put in support tickets, logs, and HTTP responses.
- A **causal chain** that shows which command spawned which
  event spawned which command — independent of OTEL's
  span-tree view.
- An **idempotency key** to deduplicate retries.

So we carry four IDs alongside W3C trace context, each doing a
different job.

## Proposed Solution

### The ID set

Five fields travel on every command, reply, and event envelope.
Each has a distinct role.

- **`:id`** — the message's own identifier. For commands, this
  is the caller-supplied idempotency key (from the
  `Idempotency-Key` header). For replies and events, a fresh
  UUIDv7. Used for dedup.
- **`:correlation-id`** — the human-readable thread for a
  single user action. Set once at the HTTP edge (from the
  `Correlation-Id` header, defaulting to `:id` if not
  supplied) and copied unchanged onto every downstream
  message in the resulting tree. The token a support engineer
  pastes into queries.
- **`:causation-id`** — the parent-child link in the message
  chain. A reply's `:causation-id` is the command's `:id`; an
  event's `:causation-id` is the commit reference; a follow-up
  command's `:causation-id` is the event that triggered it.
  Tracing causation follows this chain.
- **`:traceparent`** — W3C Trace Context. The OTEL trace and
  span IDs encoded as a string. Read on entry, populated on
  outbound messages by `telemetry/inject-traceparent`.
- **`:tracestate`** — W3C state alongside traceparent.
  Vendor-specific data; usually empty for us.

The IDs play together:

- `:correlation-id` answers *"what user action was this?"*
- `:causation-id` answers *"what triggered this specific
  message?"*
- `:traceparent` answers *"where does this fit in the trace
  tree the OTEL backend will render?"*
- `:id` answers *"is this a retry of something we already
  processed?"*

### The telemetry brick

`telemetry` wraps
[clj-otel](https://github.com/steffan-westcott/clj-otel) and
exposes a Clojure-friendly surface to domain code:

- **`telemetry/with-span`** — macro, wraps a body in a child
  span. Gracefully degrades if OTEL isn't configured.
- **`telemetry/with-span-parent`** — opens a child span under
  an explicit parent context (used on the consumer side of the
  bus to continue a trace from `traceparent`).
- **`telemetry/add-event` / `set-attribute`** — span
  enrichment.
- **`telemetry/inject-traceparent`** — extracts the W3C
  traceparent string from the *current* thread-local span, for
  embedding in outbound envelopes.
- **`telemetry/extract-parent-context`** — reads
  `traceparent` / `tracestate` from a received envelope and
  returns an OTEL context suitable for `with-span-parent`.
- **`telemetry/trace-span`** — Reitit/Sieppari interceptor
  vector that creates a server span from incoming HTTP W3C
  headers. Used by the `api` interceptor chain (see
  service-apis TDD).
- **`telemetry/counter` / `inc-counter` / `add-counter`** —
  metric primitives. The API exists; broad instrumentation
  does not yet.

The brick is the project's only consumer of OTEL libraries
(per ADR-0011); domain code never imports clj-otel directly.

### Trace propagation

#### Reading the diagrams

The diagrams follow the conventions under
[Reading the diagrams](payments-internal.md#reading-the-diagrams) in
the internal payments TDD. A note over a participant names the span it
opens and that span's parent, and a message, a changelog entry or a
stored row names the span whose `traceparent` it carries. The example
is a party whose status a request changes, and the webhook that change
sends a customer: every chain takes these hops, or a subset of them.

#### The API sends the command

```mermaid
sequenceDiagram
    box rgba(233, 236, 239, 0.5)
    participant C as Client
    end
    box rgba(165, 216, 255, 0.45)
    participant API as api-service<br/>POST /v1/parties/{party-id}/suspend
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant PC as topic-parties-command<br/>one partition, unkeyed
    participant PR as topic-parties-command-response
    end
    C->>API: request, a traceparent or none
    Note over API: trace-span opens the server span,<br/>named for the route, a child of the<br/>client's traceparent or a trace of its own
    critical transact
    Note over API,DB: fdb-transaction, a child of the server span
    API->>DB: read the idempotency entry for the key
    opt no live entry
    API->>DB: save it, pending
    end
    end
    API->>API: req->command-request stamps the server span's<br/>traceparent on the envelope
    Note over API: command-send, a child of the server span,<br/>and bus-send under it
    API->>PC: suspend-party, the server span's traceparent
    PR->>API: the party processor's reply
    API-->>C: 200, 4xx refused, 5xx unknown, retry with the key
```

The envelope is stamped before `command-send` opens, so the processor's
span is a sibling of `command-send` under the server span rather than a
child of it.

#### The processor handles it

```mermaid
sequenceDiagram
    box rgba(165, 216, 255, 0.45)
    participant PC as topic-parties-command<br/>one partition, unkeyed
    end
    box rgba(208, 191, 255, 0.45)
    participant PP as operational-processors-service<br/>party/processor
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant PR as topic-parties-command-response
    end
    PC->>PP: suspend-party, the API's server span
    Note over PP: process-command, its parent<br/>extracted from the envelope
    critical transact
    Note over PP,DB: fdb-transaction, a child of process-command
    PP->>DB: read the Party
    PP->>DB: save the Party, suspended
    PP->>DB: write party-status-changed to the parties changelog,<br/>the fdb-transaction span's traceparent
    end
    PP->>PR: the reply, process-command's traceparent
    PP-->>PC: ack
```

A changelog entry is written inside the transaction that makes the
change, so it carries that `fdb-transaction` span, and whatever the
entry later causes joins the trace beneath it.

#### The changelog relay publishes it

```mermaid
sequenceDiagram
    box rgba(165, 216, 255, 0.45)
    participant R as exclusive-dispatchers-service<br/>changelog-relay/runners
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(165, 216, 255, 0.45)
    participant PE as topic-parties-event<br/>partition-key = party
    end
    loop every 100 ms
    critical transact
    critical transact, a transaction of its own
    R->>DB: read the cursor
    end
    R->>DB: read up to 500 entries after it, at snapshot
    loop each entry
    R->>R: copy the entry's traceparent onto the event envelope
    Note over R: bus-send, with no span open,<br/>a trace of its own
    R->>PE: party-status-changed, the writer's fdb-transaction span
    end
    R->>DB: save the cursor after the last entry
    end
    end
```

The relay opens no span of its own and does not join the writer's
trace: the event carries the writer's span, so the consumer joins the
writer's trace across the relay, and the relay's `bus-send` is a trace
of one span.

#### A consumer handles the event

```mermaid
sequenceDiagram
    box rgba(165, 216, 255, 0.45)
    participant PE as topic-parties-event<br/>partition-key = party
    end
    box rgba(255, 216, 168, 0.5)
    participant W as external-adapters-service<br/>webhook/event-processor
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    PE->>W: party-status-changed, the writer's fdb-transaction span
    Note over W: process-event, its parent<br/>extracted from the envelope
    critical transact
    Note over W,DB: fdb-transaction, a child of process-event
    W->>DB: read the Party
    W->>DB: read the bank's enabled endpoints
    W->>DB: save the WebhookNotification,<br/>the fdb-transaction span's traceparent
    W->>DB: save one pending delivery per endpoint
    end
    W-->>PE: ack
```

#### A poll loop does the work later

```mermaid
sequenceDiagram
    box rgba(255, 216, 168, 0.5)
    participant O as external-adapters-service<br/>webhook/outbound-runner
    end
    box rgba(255, 236, 153, 0.5)
    participant DB as FDB
    end
    box rgba(233, 236, 239, 0.5)
    participant E as The customer's endpoint
    end
    loop every poll
    critical transact
    O->>DB: claim the due deliveries
    end
    loop each claimed delivery
    O->>DB: read its WebhookNotification and endpoint
    Note over O: webhook-delivery, its parent the<br/>traceparent stored on the notification
    O->>E: POST the notification
    E-->>O: 2xx, or anything else
    critical transact
    O->>DB: save the delivery's outcome
    end
    end
    end
```

A provider relay's intent and an `EmailDelivery` take the same shape,
under the runner spans "Message-bus boundary" names.

### HTTP edge

`telemetry/trace-span` (a vector of clj-otel
`server-span-interceptors`) is concatenated into the `api`
interceptor chain. On `:enter`:

- Extracts the W3C traceparent from incoming headers.
- Creates a server span as a child of the extracted context.
- Sets the span as the current context for the rest of the
  request (synchronous; safe because Reitit/Sieppari run on a
  single thread per request).
- Names the span for the Reitit route the request matched,
  `GET /v1/parties/{party-id}`, and records the template as
  `http.route`, since `url.path` carries resource ids.

On `:leave` or exception, records HTTP response status and ends
the span.

The handler — when it builds an outbound command envelope —
calls `telemetry/inject-traceparent` to embed the current
span's traceparent into the envelope.

### Message-bus boundary

When a processor consumes a command (or a subscriber consumes
an event):

1. Read the envelope's `:traceparent` and `:tracestate` via
   `telemetry/extract-parent-context`. This returns an OTEL
   context wrapping the parent span ID.
2. Open a child span with
   `telemetry/with-span-parent ["process-X" parent-ctx attrs
   f]`. The processor's work executes inside `f`, with the
   span set as the current thread-local context.
3. A reply sent from inside the span carries it, and a changelog
   entry or a stored row written in one of its transactions
   carries that transaction's `fdb-transaction` span, which is a
   child of it.

4. Record the outcome. `process-command` and `command-send`
   carry the reply as `command.status` — `ACCEPTED`, `REJECTED`
   or `FAILED` — and the anomaly kind as `command.reason`, and
   only a `FAILED` command marks its span as an error: a
   rejection is the bank correctly declining.

On Kafka, both ends of a hop say where the message went. `bus-send`
records `messaging.system` `kafka`, the topic as
`messaging.destination.name`, `messaging.destination.partition.id`,
`messaging.kafka.offset` and the key as `messaging.kafka.message.key`,
and the consumer's `process-command` or `process-event` records the
same for the record it read, with the consumer group as
`messaging.consumer.group.name`. The offset, group and operation are
written under the older conventions' names as well —
`messaging.kafka.message.offset`, `messaging.kafka.consumer.group` and
`messaging.operation` — which SigNoz's Messaging Queues view reads. On
the local bus, `bus-send` names the channel and nothing more.

The chain continues for as many hops as the action takes.

Work done later on a poll loop's thread, where no span is active,
continues the trace that asked for it. The record the loop claims —
a provider relay's intent, an `EmailDelivery`, a
`WebhookNotification` — stores the `traceparent` of the span that
wrote it, and the runner opens its span on that context: the relay's
`<provider>-outbound` and `<provider>-reconcile`, `email-delivery`
and `webhook-delivery`. So a request's trace runs through the
provider call it caused, the event that call's outcome publishes, and
the email or webhook it leads to. What starts a trace of its own is
what arrives from outside with none of ours: a provider's webhook
reporting back, and a scheduled run.

`fdb-transaction` records its outcome the same way, as
`fdb.outcome` — `committed`, `rejected` or `failed` — with the
anomaly kind as `fdb.reason`. A rejection rolls the transaction
back without marking the span, so an error on it is a fault.

### Logging

The `log` brick wraps `clojure.tools.logging` over SLF4J. Logs
go to logback. **Logs are not currently correlated with traces**
— there is no MDC integration that injects the OTEL trace ID
into log lines. Today, finding logs that match a span means
either (a) cross-referencing by timestamp, or (b) including
`:correlation-id` in the log message manually.

This is a real gap (see Known Limitations).

### Metrics

`telemetry/counter` and friends exist as primitives. A handful
of usages exist in infrastructure code. **Most domain
operations are not instrumented with counters today.** The API
is ready when we want to instrument; the instrumentation work
itself is largely outstanding.

### Test support

`telemetry/with-span-tests` runs a body under an in-memory OTEL
SDK, then asserts:

- Each named span exists.
- All finished spans share the same trace ID (i.e. propagation
  worked across the bus).

Used in scenario tests where trace continuity is part of the
property under test.

## Alternatives Considered

- **Correlation-ID only, no OpenTelemetry.** Simple, no library
  dependency. Rejected because real trace tooling (Jaeger,
  Tempo, Honeycomb, Datadog) needs W3C trace context to render
  span trees with timing. A correlation-id alone gives you log
  grouping but no latency view.
- **OTEL only, no correlation-id / causation-id.** OTEL trace
  IDs are technically sufficient to thread an action.
  Rejected because OTEL IDs are 128-bit hex blobs — they're
  not human-readable, not easy to put in error responses or
  support tickets, and don't expose causal links separate from
  the span tree (an OTEL span tree is shaped by *time
  containment*, not message causation).
- **Vendor-specific propagation (Zipkin B3, Datadog).**
  Rejected because OTEL with W3C is vendor-neutral; we can
  swap backends without changing instrumentation.
- **Custom trace format on the wire.** Rejected. W3C is a
  standard; building custom propagation is reinventing the
  wheel and locks us out of standard tooling.
- **Asynchronous span context propagation.** clj-otel offers
  patterns for this. Rejected for now: Reitit / Sieppari runs
  synchronously per request, and the bus consumer side reopens
  spans explicitly via `extract-parent-context` — no need for
  async context machinery.

## Known Limitations

- **No log/trace correlation.** No interceptor populates MDC
  with the active OTEL trace ID, and the logstash encoder
  doesn't include one, so a span and its log lines join only
  by timestamp. Fix is an MDC-populating interceptor from the
  active span (`log/core.clj` is a bare
  `clojure.tools.logging` wrapper) plus an
  `<includeMdcKeyName>` on the LogstashEncoder.
- **Counters exist but aren't widely instrumented.** The
  `telemetry/counter` API is in place but most domain
  operations don't increment any counters. Rejection rates,
  command throughput, latency histograms, etc. are unobserved.
  Worth a separate piece of work.
- **Structured logging lacks domain fields.** Logs are
  already JSON via `LogstashEncoder`
  (`components/resources/resources/logback.xml`), but only
  message / level / timestamp are populated. To query by
  `:correlation-id`, `:causation-id`, or command name, those
  values must be pushed into MDC and included as encoder
  fields — none are today.
- **Correlation-Id header defaults to Idempotency-Key.** When
  a client doesn't supply `Correlation-Id`, the `:id` is used.
  Two retries of the same operation share both — which is
  fine for tracing one user-intent through retries, but means
  the correlation-id space and idempotency-key space overlap.
- **Trace sampling is not configured.** Every request emits a
  full span tree. At higher volume we'd want a sampling
  strategy (head-based, tail-based, or per-route).
- **`with-span-parent` is synchronous.** It sets the current
  thread-local context. Code that hands work off to an
  executor (core.async, futures) breaks the span chain unless
  it explicitly captures and re-applies the context.
- **Counter/span functions use `!` suffix.** `inc-counter!`
  and `add-counter!` carry the bang convention from clj-otel.
  This contradicts the project's no-bang-on-side-effects
  convention (see code-style recipe). Worth a future rename
  pass on the wrapper functions.
- **A relay's publish joins no trace.** Each event carries the
  writing transaction's traceparent, but the relay's own `bus-send`
  span has no parent, so every publish is a trace of its own.

## References

- [ADR-0005](../adr/0005-error-handling-with-anomalies.md) —
  Error handling with anomalies
- [ADR-0011](../adr/0011-one-component-per-third-party-library.md) —
  One component per third-party library
- [transaction-processing.md](transaction-processing.md) —
  Transaction processing (defines envelope ID set)
- [service-apis.md](service-apis.md) — Service APIs (HTTP
  edge interceptors)
- [error-handling.md](../recipes/code/error-handling.md)
- `telemetry` brick interface
- `log` brick interface
- [W3C Trace Context](https://www.w3.org/TR/trace-context/)
- [OpenTelemetry](https://opentelemetry.io/)
- [clj-otel](https://github.com/steffan-westcott/clj-otel)
