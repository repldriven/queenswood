# 36. Simulators run in a service of their own

<!-- tessl-plugin: design -->

## Status

**Accepted**

## Context

[ADR-0019](0019-processor-packaging.md) packaged processors by service
group, and put every vendor adapter and the simulator that stands in for
it in one JVM, so an adapter reached its simulator over localhost and
the cross-pod webhook registration had no startup race to retry around.
The property wanted now is that `external-adapters-service` runs as it
would against the vendors, so what a load test measures of it is what a
deployment gets.

Sharing the JVM breaks that. A ten-minute run on kind at fifty internal
payments a second took `external-adapters-service` from 72% to 91% of
its CPU, while its heap after collection grew from 98 to 143 MB: the
simulators keep every payment they have seen, and the adapters paid for
it. A deployment against the vendors runs no simulator, so it never
pays that cost, and the run measured a service no deployment runs. The
shared JVM also let the adapter and its simulator share credentials in
memory, which no vendor does. The startup race it avoided has since
gone: the registrar from
[ADR-0034](0034-outbound-calls-go-through-a-breaker-on-their-destination.md)
asks for the adapter's subscriptions every few seconds until its breaker
closes, whichever pod starts first.

The shortlist:

- **Keep the simulators in the adapters' JVM.** Rejected: their state and
  CPU count against the adapters, and the adapter shares in memory what it
  would otherwise read from a credential.
- **A simulator beside each adapter as a sidecar.** Rejected: six more
  containers in the adapters' pod, still a shape no deployment against the
  vendors runs.
- **The simulators in a service of their own**, reached over a Service,
  each adapter and its simulator reading one provider credential from a
  Secret.

## Decision

Processors are packaged by deployment-time composition, and the
simulators run in a service apart from the adapters: one thin base per
*service group* (boilerplate main plus a require bundle registering that
group's component-kinds), and one project per group, whose
`application.yml` alone decides which processors and event consumers
that JVM hosts. The bases are group-scoped rather than one shared
superset so each project's deps carry only the bricks its group runs —
`poly check`'s unnecessary-component warning stays meaningful for these
projects instead of being structurally silenced.

Grouping is by boundary, not throughput, and a regrouping keeps two invariants:

- **`financial-processors-service`** — payment, transaction,
  interest, payee-check. Operations that post, settle, accrue, or
  gate a payment.
- **`operational-processors-service`** — bank, party,
  cash-account, idv. Operations that provision or verify the records
  money moves through; nothing posts to the ledger. Product writes
  were here until the rule in
  [ADR-0018](0018-command-writes-are-earned.md) was applied to them
  and they went back to being synchronous — a group holds whatever
  earns a processor, so it shrinks as well as grows.
- **`exclusive-dispatchers-service`** — work that must have exactly
  one dispatcher, whatever else scales: every store's changelog runner
  (a cursor has exactly one owner) and the Quartz scheduler (a trigger
  is registered per JVM). Grouped by that constraint rather than a
  domain boundary, and never grouped with domain processors — both
  would double-act on a second replica, so co-locating either pins
  whatever JVM hosts it. Putting them together is what leaves every
  other group free. Neither carries domain code: the changelog runner
  is generic, and the scheduler's tasks live in the bricks it calls.
- **`external-adapters-service`** — every external-vendor adapter,
  in one JVM, and nothing that stands in for a vendor. Grouped away
  from domain processors, not by each other: they own outbox/intent
  stores and webhook servers whose lifecycles differ from a command
  processor's. An adapter reaches its provider, real or simulated, at a
  URL from configuration, and gives it a public URL to send webhooks
  to.
- **`external-simulators-service`** — the simulator that stands in for
  each vendor, and the payment scheme they share, in one JVM of their
  own, so their state and CPU count against nothing an installation
  runs against the vendors. An installation that runs against the
  vendors disables it.
- **Each provider credential is a Secret of its own**, named for the
  credential and holding the keys an adapter and its provider each read
  half of: `modulr-api`, `form3-api`, `clearbank-client-key` and
  `clearbank-provider-key`. Where the simulators run, a Job generates
  any that is empty and the simulator reads the same Secret; against
  the vendors, an ExternalSecret fills each from its Secret Manager
  entry. The adapter reads the same names and keys either way.
- **Financial and operational processors never share a JVM.** A poison message,
  memory spike, or deploy of a provisioning domain must not sit in the same
  failure domain as money movement.
- **Cursor continuity.** message-bus consumer groups and changelog
  `consumer-id`s move with the processor, verbatim. The subscription
  and changelog cursor identify the *consumer role*, not the pod
  that happens to host it; renaming one abandons a cursor and
  re-consumes or skips.
- **Exclusive work lives in one group, nowhere else.** A changelog
  cursor and a cron trigger each admit exactly one dispatcher, so a
  group hosting either is pinned to `replicas: 1`.
  `exclusive-dispatchers-service` is pinned by design; every other
  group is free of the constraint precisely because it hosts none.
  A poll loop is not exclusive work on its own. A runner that claims
  each row by a conditional transition inside one FDB transaction
  leaves a second replica with nothing to take, so the group hosting
  it stays free. `external-adapters-service` relies on that already:
  it hosts the ClearBank outbound intent runner, which is not pinned
  and drains the pending-intent index. That runner claims nothing
  yet: it tolerates a second replica only because ClearBank
  deduplicates a retried POST on `endToEndIdentification`, so the
  transactional claim is what a new runner in that group carries
  instead.
  Note that freedom is necessary but not sufficient — every topic is
  currently single-partition and a store that declares no
  `ordering_key` publishes unkeyed, so raising replicas buys standbys
  rather than throughput until both change. See
  [ADR-0021](0021-changelog-relay.md).

## Consequences

Easier:

- `external-adapters-service` runs the same code, config and credentials
  in a load test as against the vendors, and its CPU and heap are its own.
- An installation drops the simulators by disabling one service, and with
  it the Job that generates their credentials.
- Each provider credential is one Secret with one shape, so moving an
  installation from a simulator to the vendor changes what fills it and
  nothing that reads it.
- A new processor no longer scaffolds a base and a service: it adds its
  brick, its `bank/X.yml`, and message-bus wiring to the group its
  boundary dictates.
- Regrouping is configuration and plumbing, never brick code.

Harder:

- An adapter's first calls to its simulator can fail until the simulator
  listens; the breaker and the registrar retry them, and readiness stays
  down meanwhile.
- An installation against the vendors fills four Secrets through
  ExternalSecrets before its adapters start, since each reference is
  required.
- Failure isolation within a group is gone by design — the financial
  group accepts that a payee-check DLQ storm shares a pod with payment
  consumption; the boundary rule keeps the blast radius on one side of the
  financial line.
- Per-domain resource attribution needs metrics, not `kubectl top`.
- The adapters' `_URL` and `_ADAPTER_PUBLIC_URL` values name the two
  Services' ports, and nothing checks them against the ports each server
  listens on. Drift shows as an adapter's readiness staying down, and the
  chart's values beside each `*-server.yml`'s port is what to compare.
