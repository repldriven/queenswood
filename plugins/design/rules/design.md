# Queenswood system design

How Queenswood specifically is built on top of Polylith and mono —
persistence, system wiring, reactive state, the API surface, and how
work gets packaged for deployment. Queenswood's own architectural
choices, not portable Polylith or Clojure conventions.

## Queenswood consumes `mono` as a pinned dependency

Consume `mono` as a pinned git-dependency, not a fork. The workspace
holds only Queenswood's domain bricks (`com.repldriven.queenswood.*`);
shared infrastructure comes from `com.repldriven/mono`
(`com.repldriven.mono.*`), pinned to a tag and sha in the `deps/mono`
and `deps/mono-test` shims, which every project references as
`ext/mono` — a project's `:test` alias re-points it at the test
superset — so upgrading mono is a one-line bump there. mono's
practices — its ADRs, recipes, slide deck, Tessl plugins, hook
library, semgrep rules and justfiles — are laid down by
`just mono-import` at the sha `deps/mono-dev` pins, untracked and never
edited here, and may move ahead of or behind the code. The two
repositories share one ADR number space and one recipe namespace, and
the import refuses to overwrite a path this tree tracks.
See [ADR-0001](../../../docs/adr/0001-reuse-mono-as-upstream.md).

## Persistence is FoundationDB Record Layer

Use FoundationDB with the Record Layer for all persistence. Each
entity type lives in its own record store, and an operation spanning
multiple stores runs inside a single FDB transaction.
See [ADR-0002](../../../docs/adr/0002-foundationdb-record-layer.md).

## Traces go to SigNoz, in the cluster that produces them

Every deployment of the chart runs SigNoz in its own cluster, and every
service sends its traces there over OTLP/HTTP. SigNoz is a dependency
of the queenswood chart from `charts.signoz.io`, pinned and on by
default, and each service's `OTEL_ENDPOINT` points at its collector
unless `otel.endpoint` names another. Provision SigNoz's root user at
startup, because its collector refuses OTLP until an organisation
exists and that user creates one; generate its password in the
cluster, by a Job, into `queenswood-signoz-root`, keep it nowhere else,
and type a pair only in `values-local.yaml` and
`infra/signoz/casting.yaml`. Turn SigNoz's stats reporter off wherever
it runs. Run the monolith loop's SigNoz from `infra/signoz/casting.yaml`
through the `foundryctl` that `justfiles/telemetry.just` pins, with the
chart's images and its UI on 3301, since the monolith holds 8080.
Export traces only: logs and metrics over OTLP are a change to mono's
telemetry component, made there.
See [ADR-0031](../../../docs/adr/0031-traces-go-to-signoz-in-the-cluster-that-produces-them.md).

## SigNoz is configured through its operator

Declare SigNoz's content in the queenswood chart as the SigNoz
Operator's resources, and let the operator create and keep it in each
cluster's SigNoz. The operator's chart is vendored under
`charts/signoz-operator` with its CRDs in `crds/`, because upstream
ships them as templates Helm would not install ahead of the chart's own
resources. The operator signs in as the SigNoz service account
`queenswood-operator`, holding `signoz-editor`, whose API key a Job
issues in the cluster into `queenswood-signoz-operator-key` by signing
in as the root user, and the key is kept nowhere else. One
`ProviderConfig` names SigNoz at its Service, the same address in every
cluster. A `Dashboard` is rendered per file under
`files/signoz/dashboards/` and a `SavedView` per file under
`files/signoz/views/`, each the body SigNoz's API takes, and no field
the operator writes a default into is set on either.
See [ADR-0032](../../../docs/adr/0032-signoz-is-configured-through-its-operator.md).

## Record meta-data evolves by declared versions

Every change to a record type, primary key or index bumps the
meta-data `version` in the declaration, `fdb-record-types.yml`. An
index whose key, type or uniqueness changed keeps its name and its
`added` and takes the new version as `modified` — never a rename in
place of the bump; a new index takes the new version as both `added`
and `modified`; a store added after the first version takes it as
`since`, declared there rather than as a proto option. An index's
`added` and a record type's `since` never change afterwards. A removed
or renamed index is listed under its store's `former-indexes` with its
`name`, its `added` and the version it was removed at, and a new index
never takes a former index's name. A proto field no longer wanted is
deprecated with its tag kept and dropped in the record conversion —
never removed, nor its tag reserved, once a record has been written
with it. Never clear a store's meta-data to make a refused save land.
`just test-all` runs the guard whatever changed.
Commands: `just test-all`.
See [schema-evolution](../../../docs/recipes/code/schema-evolution.md).

## A service's `application.yml` includes shared groups, never copies

A service's system definition lives in its project's
`resources/application.yml`, with domain-scoped includes under
`resources/bank/`. A group that exists under
`components/resources/resources/system/` is included, never inlined as
a copy: an inlined block has no `!include` for `config-includes-resolve`
to follow, and nothing loads a project's production `application.yml`,
so it can name a deleted component-kind and still pass every check.
Check a change with `just test-all`, whose `test-startup` component
loads every deployable project's production config against its own
classpath and fails on a `system/component-kind` nothing registers.
Commands: `just test-all`.
See [service-configurations](../../../docs/recipes/code/service-configurations.md).

## React to a changelog, don't orchestrate across bricks

Reactive state transitions run through the changelog relay, not an
HTTP-layer orchestrator: a request N bricks must react to is N
consumers, each in the reacting brick. The tier that owns a cursor holds
no domain logic, and the brick that holds domain logic owns no cursor. A
store's write co-commits a `ChangelogEvent` envelope to its changelog —
the adapter outbox protos reuse its field numbers, so their entries
decode as one too; a `changelog-relay` runner, hosted in
`exclusive-dispatchers-service` and scaled by sharding stores across
deployments rather than by replicas, tails that cursor and republishes
the payload verbatim to the message bus, never deserialising it, with
`{:deduplicate? false}` because collapsing two transitions would drop
an event; the reacting brick handles the event in its own `events.clj`,
against its own records, outside any changelog transaction. Delivery is
at-least-once — the cursor advances only when the whole pass commits,
so a handler that throws leaves the checkpoint in place and the entry
is redriven — and the source-status guard in `domain.clj` is what makes
that safe. Never mint a fresh `consumer-id` for an existing cursor — it
starts at no checkpoint and rescans the store's whole history in one
transaction, republishing every historical transition. Not every
envelope field crosses: the relay carries `event_name`, `payload`,
`correlation_id`, `causation_id` and `traceparent` into mono's
`EventEnvelope`, carries `event_id` as its `id` so a consumer dedups on
the entry rather than on an id the publisher minted, and hands
`ordering_key` to the bus as the publish key, while `dedup_key` and
`created_at` stop there — a consumer needing either reads it from the
Avro payload. Consume with the reacting brick's own
`<brick>/event-processor` kind wrapped in mono's
`event-processor/event-processor`, which leaves an event unacknowledged
when the handler throws or returns an anomaly, so the bus redelivers
it. A brick acts only on its own records, the webhook component
excepted: it reads across domains through each catalogued domain's
`*-query` brick, so a notification body equals what that domain's read
route returns.
See [ADR-0021](../../../docs/adr/0021-changelog-relay.md).

## A write earns command status, or stays synchronous

A write becomes a command — sent over the bus to a processor — only
when it has at least one of four intrinsic properties: multi-record
atomicity under contention (spans records or bricks, must commit or
abort as one), idempotency stakes (a redelivered or double-submitted
write causes real damage), reaction (other bricks must respond
asynchronously via the changelog), or unreliable ingress (originates
from a webhook or external event needing consume-then-ack semantics).
A write with none of these stays synchronous; a path moves when it
crosses a criterion, never for symmetry. Events exist to cross
boundaries, not to rebuild state: FDB records stay the source of truth
and the changelog-as-outbox is the event backbone — no event sourcing.
A commandified brick gains a `-query` sibling only once a reader
outside its processor needs its reads, and then splits into two bricks:
`X-query` (reads only — `get-*` / `find-*` / `count-*` — plus the read
primitives the write side needs in a transaction, and the only
cash-account-style brick `api` may require) and `X` (commands, writes,
domain, events), which keeps the plain name, depends on `X-query` and
calls its reads inside its own FDB transaction, passing the live `txn`.
`components/X-query/` existing is what marks `X` as a guarded write
brick: the pre-commit guardrail (`scripts/hooks/enforce-idioms.sh`)
fails `api` request code that requires such a brick's interface, the
`system.clj` registration bundle excepted, and `poly check` hard-fails
any reference once a service project no longer lists the write brick.
See [ADR-0017](../../../docs/adr/0017-query-write-brick-split.md),
[ADR-0018](../../../docs/adr/0018-command-writes-are-earned.md).

## Processors are packaged by deployment-time composition

Processors are packaged by deployment-time composition: one thin base
per service group (a boilerplate main plus a require bundle registering
that group's component-kinds) and one project per group, whose
`application.yml` alone decides which processors and event consumers
that JVM hosts. Bases are group-scoped, not one shared superset, so each
project's deps carry only the bricks its group runs. Group by
boundary, not throughput — financial processors (payment,
transaction, interest, payee-check) never share a JVM with
operational processors (bank, party, cash-account, idv); external
adapters and simulators are never grouped with domain processors, and
share one JVM of their own. Work that admits exactly one dispatcher —
every store's changelog runner and the Quartz scheduler — goes in
`exclusive-dispatchers-service`, pinned to `replicas: 1`, which is what
leaves every other group free of the constraint. A poll loop is not
exclusive work on its own: a runner that claims each row by a
conditional transition inside one FDB transaction leaves a second
replica nothing to take, so its group stays free — that claim is what a
runner added to `external-adapters-service` carries instead of a pin.
When a processor moves between groups its consumer groups and changelog
`consumer-id`s move with it verbatim, or the cursor is abandoned.
See [ADR-0019](../../../docs/adr/0019-processor-packaging.md).

## A bank chooses its providers when it is created

A bank chooses one provider of each kind its installation offers —
payments and identity verification today — when it is created, and
keeps them for its life. Record them at creation as one provider per
kind, each defaulting to the installation's default for its kind where
the create names none, whoever creates the bank. Refuse a create naming
a provider the installation does not run, and never change a bank's
provider afterwards: moving a running bank is a migration, not a
request. Declare every provider an installation runs by name, with one
default per kind, and check each adapter at start-up against its own
declaration. Give each provider its own command channels and route a
command to the bank's provider's channel from configuration; share the
event channels, whose ids and addresses are unique across providers.
Read the bank's provider's declaration wherever behaviour follows a
declaration, never the installation's. A domain component never names a
provider: it holds the bank's provider as a key into configuration and
branches on the declaration the key selects, never on the key. Offer a
new kind of provider the same way — a declaration per provider, a
default, a channel per provider and the kind's entry on the bank — with
no change to how the bank records the others. The rest of ADR-0020
stands: a vendor's HTTP contract lives in its adapter alone, anomaly
kinds stay provider-neutral, and a company register stays one per
installation.
See [ADR-0030](../../../docs/adr/0030-a-bank-chooses-its-providers-when-it-is-created.md).

## One API, fully OpenAPI-compliant

Expose one HTTP API for the whole bank — one base (`api`), one
base URL, one OpenAPI document, bank-shaped rather than
implementation-shaped (a consumer integrates with "Queenswood", not
with each domain separately). The decomposition into domain processors
lives behind it on the command pipeline, so processors split, merge and
scale without changing the external contract. Treat full OpenAPI 3.x
compliance as the contract itself: request/response bodies are named
components referenced by `$ref`, never inlined; API-key auth is a
`securitySchemes` entry applied per-operation, an endpoint needing none
opting out explicitly; every operation carries realistic examples,
reusable `components/examples` where one shape recurs across endpoints;
every 2xx/4xx/5xx response shape is documented, with the
rejection/error shape itself a reusable component; polymorphic payloads
project as `oneOf` plus `discriminator`; the exported spec is validated
against the OpenAPI 3.x schema in CI, served at the API root in
development and published as a static artefact for consumers.
See [ADR-0013](../../../docs/adr/0013-single-unified-api.md),
[ADR-0014](../../../docs/adr/0014-openapi-3x-compliance.md).

## Lifecycle transitions guard their source state

A transition function in `domain.clj` asserts its source state as
the first `let-nom>` binding, before any capability or limit check,
rejecting with a per-brick `:<entity>/invalid-status` kind and a
payload carrying `:message`, the entity's id key, `:status`, and
`:allowed`. An event handler's transition leg gates on the loaded
record's current status matching the expected source and skips
silently rather than rejecting — delivery is at-least-once, a handler
that throws leaves the checkpoint in place and the entry is redriven,
so redelivery and replay must be a no-op, not a failure.
`:<entity>/invalid-status` maps to HTTP 409 in `api`'s
rejection→status table. No shared `utility` guard helper until three or
more bricks have landed identical guard shapes. Adding a new lifecycle
state or transition works through a ten-point checklist: proto enum,
Avro schema registered in both YAMLs, the `domain.clj` guard,
`core.clj` orchestration, `commands.clj` dispatch, `interface.clj` fn,
the `events.clj` leg if two-phase, the `api` route/OpenAPI/rejection
mapping, the event channel and consumer wiring, and tests.
See [lifecycle-transitions](../../../docs/recipes/code/lifecycle-transitions.md),
[ADR-0021](../../../docs/adr/0021-changelog-relay.md).

## System-level tests prove model equality

System-level correctness is proven by model-equality property
testing: a pure-functional reimplementation of the bank's domain
rules (the model) imports nothing from production — no FDB, message
bus, protobuf, Malli, nom or real IDs, not even `policy`, whose rules
it re-implements itself — and runs in parallel with the real system
against command sequences fugato generates from a model spec
(`{:run? :args :next-state :valid? :freq}`). Projection functions
reduce real-system state to the model's shape; the property is equality
between the model's end-state and the projected real-system end-state,
and fugato shrinks any divergence to a minimal reproducer.
Hand-authored EDN scenarios share the same runner and projections, for
cases locked down explicitly. Three test-only components carry it, in
`project:dev` and in no deployable project: `test-model` (the model),
`test-projections` (projections built on production component
interfaces only) and `test-scenarios` (command dispatch, ID side-table,
quiescence wait, divergence debugging). The dependency arrow points
test → production, never the reverse — Polylith fails the build when a
production component imports from `test-*`.
See [ADR-0009](../../../docs/adr/0009-model-equality-property-testing.md).

## Bank-specific code generation follows the shared prep-lib pattern

Generate code from a source artefact with Clojure's standard
`:deps/prep-lib` mechanism, never an external build system — a Maven
plugin, Gradle or a Babashka task. A `build.clj` co-located in the
brick delegates the work to `bases/build`, where new generation logic
goes first so other bricks can reuse it. Generated code lands in a
`gen/` folder inside the brick, with a `gen/.gitignore` of `*` and
`!.gitignore`, and is never committed. After a source-schema change
`:force true` is required — the prep marker is stale.
See [code-generation](../../../docs/recipes/code/code-generation.md).
