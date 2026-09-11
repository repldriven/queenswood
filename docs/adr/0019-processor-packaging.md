# 19. Processor packaging is deployment-time composition

<!-- tessl-plugin: design -->

## Status

Accepted.

## Context

Every command processor originally shipped as its own microservice:
a `X-processor` base (boilerplate `main.clj` plus a require
bundle) and a `X-processor-service` project (message-bus wiring plus
the domain's system YAML). Ten processor deployments existed, each
under-utilised — ten JVMs paying the FDB Record Layer and message-bus
client footprint to consume a trickle of commands, ten CI project
runs, ten Helm entries.

The unit of *code* was never the service. A processor's behaviour
lives in its `X` brick; the base contributes nothing but
component-kind registration, and the project's `application.yml` is
what actually composes a running system. The monolith already runs
every processor in one JVM. Packaging was per-domain out of
uniformity, not necessity.

## Decision

Processors are packaged by deployment-time composition: one thin
base per *service group* (boilerplate main plus a require bundle
registering that group's component-kinds), and one project per
group, whose `application.yml` alone decides which processors
and event consumers that JVM hosts. The bases are
group-scoped rather than one shared superset so each project's
deps carry only the bricks its group runs — `poly check`'s
unnecessary-component warning stays meaningful for these projects
instead of being structurally silenced.

Grouping is by boundary, not throughput:

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
- **`external-adapters-service`** — every external-vendor adapter
  and the simulator that stands in for that vendor, in one JVM.
  Grouped away from domain processors, not by each other: they own
  outbox/intent stores and webhook servers whose lifecycles differ
  from a command processor's. An adapter reaches its simulator over
  localhost rather than a Service, which removes the startup race
  the cross-pod webhook registration had to retry around.

Financial and operational processors never share a JVM: a poison
message, memory spike, or deploy of a provisioning domain must not
sit in the same failure domain as money movement.

Two invariants when regrouping:

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

- A handful of deployments instead of one per domain; CI's
  per-project matrix and the Helm/bake/release inventories shrink to
  match.
- Regrouping is configuration and plumbing, never brick code.
  Promoting a hot domain to its own deployment (or moving one
  between groups) relocates its YAML, message-bus wiring, bundle require,
  and deps — the `X` brick itself is untouched.
- A new processor no longer scaffolds a base and a service: it adds
  its brick, its `bank/X.yml`, and message-bus wiring to the group its
  boundary dictates.

Harder:

- Failure isolation within a group is gone by design — the financial
  group accepts that a payee-check DLQ storm shares a pod with
  payment consumption; the boundary rule keeps the blast radius on
  one side of the financial line.
- Per-domain resource attribution needs metrics, not `kubectl top`.
- Moving a processor between groups touches three places instead of
  one: the domain YAML and message-bus wiring move between projects, the
  brick moves between the two bases' require bundles, and the deps
  move between the two projects' `deps.edn`s.
