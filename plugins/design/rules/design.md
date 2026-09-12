# Queenswood system design

How Queenswood specifically is built on top of Polylith and mono —
persistence, system wiring, reactive state, messaging, the API
surface, and how work gets packaged for deployment. Queenswood's own
architectural choices, not portable Polylith or Clojure conventions.

## Queenswood consumes `mono` as a pinned dependency

Queenswood consumes `mono` as a pinned git-dependency, not a fork. The
workspace holds only Queenswood's own domain bricks
(`com.repldriven.queenswood.*`); shared infrastructure comes from
`com.repldriven/mono` on the classpath (`com.repldriven.mono.*`), pinned
to a tag/sha via the `ext/mono` shims under `deps/`. An improvement that
isn't domain-specific belongs in `mono` — made there, released, and
pulled down by bumping the shim; upgrading mono is that one-line bump.
See [ADR-0001](../../../docs/adr/0001-reuse-mono-as-upstream.md).

## Persistence is FoundationDB Record Layer

Use FoundationDB with the Record Layer for all persistence. Each
entity type lives in its own record store; an operation spanning
multiple stores runs inside a single FDB transaction — the mechanism
multi-record atomicity depends on.
See [ADR-0002](../../../docs/adr/0002-foundationdb-record-layer.md).

## Record meta-data evolves by declared versions

`fdb-record-types.yml` declares the meta-data `version`, every index
its `added` and `modified` versions, and under a store's
`former-indexes` the indexes removed from it. Every change to a record
type, primary key or index bumps `version`; a store added after the
first version takes it as `since`, declared there and never as a proto
`since_version` option; an index whose key, type or uniqueness changed
keeps its name and its `added` and takes the new version as `modified`;
a new index takes it as both; a removed or renamed
index becomes a former entry with its `name`, `added` and `removed`, and
its name is never reused. A proto field, once written,
is deprecated with its tag kept and dropped in the record conversion —
never removed or reserved. The migrator saves with a validator that
allows index rebuilds and refuses everything else: a change at the
stored version, or older meta-data than the store's, fails the Job
rather than being skipped. Run `just test-schema-evolution` before
pushing — it validates the working tree's meta-data as an evolution of
the last `stable-*` tag's.
See [schema-evolution](../../../docs/recipes/code/schema-evolution.md).

## System components are declared in YAML, registered in Clojure

Component lifecycle runs through `donut.system`, with two layers per
component kind. Implementation registers via `system/defcomponents`
(start/stop fns, config schema, instance schema) from `system.clj`,
or `system/core.clj` aggregating a `system/` folder once a brick has
two or more definition clusters — never called directly from
`interface.clj`. A brick's `interface.clj` bare-requires that system
namespace, in the bracketed unaliased form, so multimethods extend on
load. Declaration lives separately, in a YAML system file with a
top-level `system:` key: each component instance uses
`!system/component` naming a registered `system/component-kind`; a
slot the bootstrap must inject is `!system/required-component`,
resolved before `system/start`. Reference another component with
`!system/ref` / `!system/local-ref` — a bare string is never promoted
to a ref, and an unregistered kind fails to start. Don't bake an
environment name into a shared resource component or its config.
A group that exists under `components/resources/resources/system/` is
included, never inlined as a copy: an inlined block has no `!include`
for `config-includes-resolve` to follow, and nothing loads a project's
production `application.yml`, so it can name a deleted component-kind
and still pass every check. Tests consolidate system-component bare
requires for a base or project into one `test/.../system.clj` namespace
rather than repeating them per file.
See [ADR-0007](../../../docs/adr/0007-system-as-data.md),
[system-components](../../../docs/recipes/code/system-components.md),
[system-configurations](../../../docs/recipes/code/system-configurations.md).

## React to a changelog, don't orchestrate across bricks

Reactive state transitions run through the changelog relay, not an
HTTP-layer orchestrator. A store's write co-commits a `ChangelogEvent`
envelope to its changelog; a `changelog-relay` runner tails that cursor
and republishes the payload to the message bus; the reacting brick
handles the event in its own `events.clj`, against its own records. The
relay holds no domain logic, and no handler runs inside the changelog
checkpoint transaction. Never mint a fresh `consumer-id` for an
existing cursor — it starts at no checkpoint and rescans the store's
whole history in one transaction. Not every envelope field crosses:
the relay carries `event_name`, `payload`, `correlation_id`,
`causation_id` and `traceparent` into mono's `EventEnvelope`, carries
`event_id` as its `id`, and hands `ordering_key` to the bus as the
publish key, while `dedup_key` and `created_at` stop there — a
consumer needing either reads it from the Avro payload. Consume with
the reacting brick's own `<brick>/event-processor` kind wrapped in
mono's `event-processor/event-processor`, which leaves an event
unacknowledged when the handler throws or returns an anomaly. A brick
acts only on its own records, the webhook component excepted: it reads
across domains through each catalogued domain's `*-query` brick, so a
notification body equals what that domain's read route returns.
See [ADR-0021](../../../docs/adr/0021-changelog-relay.md).

## The message bus stays behind an abstraction

Keep the message bus behind an abstraction — the `message-bus`
brick's `Producer` / `Consumer` protocols, never a backend directly.
Two implementations ship: `pulsar` for production, and a
Clojure-channels `local` backend for tests and small-footprint
deployments.
See [ADR-0003](../../../docs/adr/0003-message-bus-abstraction.md).

## Messaging payloads are Avro

Command and event payloads on the message bus are Avro, via
Lancaster. Schemas live in `schema` alongside the protobuf
record definitions; producers and consumers bind to a schema at
registration, so a mismatch is caught at startup, not in production.
See [ADR-0004](../../../docs/adr/0004-avro-for-message-payloads.md).

## A write earns command status, or stays synchronous

A write becomes a command — sent over the bus to a processor — only
when it has at least one of four intrinsic properties: multi-record
atomicity under contention (spans records or bricks, must commit or
abort as one), idempotency stakes (a redelivered or double-submitted
write causes real damage), reaction (other bricks must respond
asynchronously via the changelog), or unreliable ingress (originates
from a webhook or external event needing consume-then-ack semantics).
A write with none of these stays synchronous. Once a domain earns
commands, it splits into two bricks: `X-query` (reads only —
`get-*` / `find-*` / `count-*` — the only cash-account-style brick
`api` may require) and `X` (commands, writes, domain,
events), which depends on `X-query` and calls its reads inside
its own FDB transaction, passing the live `txn`.
See [ADR-0017](../../../docs/adr/0017-query-write-brick-split.md),
[ADR-0018](../../../docs/adr/0018-command-writes-are-earned.md).

## Processors are packaged by deployment-time composition

Processors are packaged by deployment-time composition, not one
microservice per domain: one thin base per service group (a
boilerplate main plus a require bundle registering that group's
component-kinds) and one project per group, whose `application.yml`
alone decides which processors and event consumers that
JVM hosts. Bases are group-scoped, not one shared superset, so each
project's deps carry only the bricks its group runs. Group by
boundary, not throughput — financial processors (payment,
transaction, interest, payee-check) never share a JVM with
operational processors (bank, party, cash-account,
cash-account-product, idv); external adapters and simulators are
never grouped with domain processors, and share one JVM of their
own. Work that admits exactly one dispatcher — every store's
changelog runner and the Quartz scheduler — goes in
`exclusive-dispatchers-service`, pinned to `replicas: 1`, which is
what leaves every other group free of the constraint. A poll loop is
not exclusive work on its own: a runner that claims each row by a
conditional transition inside one FDB transaction leaves a second
replica nothing to take, so its group stays free — that claim is
what a runner added to `external-adapters-service` carries instead
of a pin. When a processor moves between groups its consumer groups
and changelog `consumer-id`s move with it verbatim, or the cursor is
abandoned.
See [ADR-0019](../../../docs/adr/0019-processor-packaging.md).

## External providers are deployment facts

Which external provider answers is settled by which adapter service
runs and how it is configured — never by a request parameter validated
against a list. Pluggability comes from the command channel: a second
provider is a second adapter base consuming the same channel, routed by
configuration and consumer groups, not by a conditional inside a brick.
A domain component (`company`, `idv`, `payment`) never names a provider
and never takes a provider parameter; the vendor's HTTP contract — the
outbound call and the translation of its wire shape — lives in that
vendor's adapter, the only thing named after it. Anomaly kinds stay
provider-neutral even when raised inside a vendor's adapter, because
they surface as the API's RFC 9457 `type`. Recording a provider is
still fine: if a value selects behaviour it is dispatch and belongs in
deployment, but if it only records what happened it is provenance and
may travel on the reply.
See [ADR-0020](../../../docs/adr/0020-providers-are-deployment-facts.md).

## One API, fully OpenAPI-compliant

Expose one HTTP API for the whole bank — one base (`api`), one
base URL, one OpenAPI document, bank-shaped rather than
implementation-shaped (a consumer integrates with "Queenswood", not
with each domain separately). Treat full OpenAPI 3.x compliance as
the contract itself: request/response bodies are named components
referenced by `$ref`, never inlined; API-key auth is a
`securitySchemes` entry applied per-operation; every operation
carries realistic examples; every 2xx/4xx/5xx response shape is
documented, with the rejection/error shape itself a reusable
component; polymorphic payloads project as `oneOf` plus
`discriminator`; the exported spec is validated against the OpenAPI
3.x schema in CI.
See [ADR-0013](../../../docs/adr/0013-single-unified-api.md),
[ADR-0014](../../../docs/adr/0014-openapi-3x-compliance.md).

## Lifecycle transitions guard their source state

A transition function in `domain.clj` asserts its source state as
the first `let-nom>` binding, before any capability or limit check,
rejecting with a per-brick `:<entity>/invalid-status` kind and a
payload carrying `:message`, the entity's id key, `:status`, and
`:allowed`. An event handler's second leg gates on the loaded
record's current status matching the expected source and skips
silently rather than rejecting — redelivery and replay must be a
no-op, not a failure. `:<entity>/invalid-status` maps to HTTP 409 in
`api`'s rejection→status table. Adding a new lifecycle state or
transition works through a ten-point checklist: proto enum, Avro
schema registered in both YAMLs, the `domain.clj` guard, `core.clj`
orchestration, `commands.clj` dispatch, `interface.clj` fn, the
`events.clj` leg if two-phase, the `api` route/OpenAPI/rejection
mapping, the event channel and consumer wiring, and tests.
See [lifecycle-transitions](../../../docs/recipes/code/lifecycle-transitions.md),
[ADR-0021](../../../docs/adr/0021-changelog-relay.md).

## System-level tests prove model equality

System-level correctness is proven by model-equality property
testing: a pure-functional reimplementation of the bank's domain
rules (the model) imports nothing from production — no FDB, Pulsar,
protobuf, Malli, nom, or real IDs — and runs in parallel with the
real system against fugato-generated command sequences. Projection
functions reduce real-system state to the model's shape; the
property is equality between the model's end-state and the projected
real-system end-state, and fugato shrinks any divergence to a
minimal reproducer. Hand-authored EDN scenarios share the same
runner and projections.
See [ADR-0009](../../../docs/adr/0009-model-equality-property-testing.md).

## Testcontainer infrastructure follows the three-layer pattern

Testcontainer-backed infrastructure follows a three-layer pattern:
the container itself, an extractor that reads runtime values (host,
port, cluster-file-path) from the started container — living in the
relevant brick's `system/` folder, never in `testcontainers` itself
— and the high-level component, which consumes extracted values
exactly as it would a production literal and never branches on
whether it's running against a container. The `testcontainers` brick
may call builder-pattern setup methods during construction, never
library methods against a started container.
See [testcontainers](../../../docs/recipes/test/testcontainers.md).

## Bank-specific code generation follows the shared prep-lib pattern

Code generation from a source artefact (currently protobuf record
definitions for `schema`) uses Clojure's standard
`:deps/prep-lib` mechanism, never a build-system plugin or a custom
run-script. Each brick's `deps.edn` declares `:deps/prep-lib` with an
`:fn` entry point and an `:ensure` path marking prep as up-to-date; a
co-located `build.clj` implements the generation, delegating to
`bases/build` so other bricks can reuse it. Generated code lands in
a `gen/` folder with its own `.gitignore` (`*` / `!.gitignore`) —
never committed. Regeneration is deliberate:
`clj -X:deps prep :aliases '[:dev]'`; after a source-schema change,
`:force true` is required — the `:ensure` marker doesn't detect
staleness on its own.
See [ADR-0010](../../../docs/adr/0010-code-generation-via-prep-lib.md),
[code-generation](../../../docs/recipes/code/code-generation.md).
