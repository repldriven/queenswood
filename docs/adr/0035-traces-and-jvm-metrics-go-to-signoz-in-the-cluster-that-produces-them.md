# 35. Traces and JVM metrics go to SigNoz, in the cluster that produces them

<!-- tessl-plugin: design -->

## Status

**Accepted**

## Context

[ADR-0031](0031-traces-go-to-signoz-in-the-cluster-that-produces-them.md)
put SigNoz in every cluster and sent it every service's traces, and only
its traces. The property wanted now is that a service's latency can be
read beside the JVM it ran in: when a payment's command takes longer
through a sustained run, whether garbage collection, heap or CPU moved
with it.

Without it a trace shows that a step slowed and never why. A load test
on kind saw every stage of an internal payment slow by a tenth over ten
minutes, and could not tell collection pauses from contention on the
cluster's CPUs, because no service reports either.

The shortlist:

- **The OpenTelemetry Java agent beside each service.** Rejected: a
  second SDK in every JVM next to mono's, and an agent jar in every
  image.
- **A collector of the cluster's own**, such as SigNoz's `k8s-infra`
  DaemonSet, scraping the JVMs and the nodes. Rejected: a second
  collector beside SigNoz's, each to configure and keep from clashing.
- **A JMX exporter in each JVM and a scrape by SigNoz's collector.**
  Rejected: an agent per image and a scrape target per service, for
  what the SDK already in the JVM can send.
- **The JVM's runtime metrics from mono's telemetry component.** The SDK
  each service starts adds a meter provider and the runtime telemetry
  where it is given a metrics endpoint, and pushes over OTLP to the
  collector its traces already go to.

## Decision

Every deployment of the chart runs SigNoz in its own cluster, and every
service sends its traces and its JVM's runtime metrics there over
OTLP/HTTP, through mono's `telemetry/otel-sdk`.

The parts:

- Take SigNoz as a dependency of the queenswood chart from `charts.signoz.io`,
  pinned and on by default, and point each service's `OTEL_ENDPOINT` at its
  collector's `/v1/traces` and `OTEL_METRICS_ENDPOINT` at its `/v1/metrics`,
  unless `otel.endpoint` or `otel.metricsEndpoint` names another.
- Set the metrics endpoint on the services' Deployments, not on the bootstrap or
  migrator Jobs, which end before a reading means anything.
- Run no OpenTelemetry Java agent and no collector beside SigNoz's: a metric a
  service should report is a change to mono's telemetry component, made there.
- Provision SigNoz's root user at startup, because its collector refuses OTLP
  until an organisation exists and that user creates one.
- Generate the root user's password in the cluster, by a Job, into
  `queenswood-signoz-root`, and keep it nowhere else. Type a pair only where one
  is meant to be typed: `values-local.yaml` and `infra/signoz/casting.yaml`.
- Turn SigNoz's stats reporter off wherever it runs.
- Run the monolith loop's SigNoz from `infra/signoz/casting.yaml` through the
  `foundryctl` that `justfiles/telemetry.just` pins, with the chart's images and
  its UI on 3301, since the monolith holds 8080.
- Export no logs over OTLP: that too is a change to mono's telemetry component.

## Consequences

Easier:

- A slow stage in a trace can be set against the same service's
  garbage collection, heap, threads and CPU, by the minute, in the same
  tool.
- No service changed its code: an endpoint in the environment turns
  the metrics on, and a blank one leaves them off.
- A trace survives SigNoz restarting, and an incident's traces are
  there when somebody looks.

Harder:

- Every service exports a batch of metrics every ten seconds, and
  ClickHouse keeps them with the spans, on the same volumes.
- Every cluster runs ClickHouse, its operator, ZooKeeper, a collector
  and the SigNoz server: gigabytes where Jaeger took a quarter of one.
- An instance's traces and metrics go with its cluster. Shelving it or
  rebuilding its cluster loses them.
- Reading SigNoz as its root user on an instance needs the cluster
  access that reads the Secret.
- SigNoz is pinned twice, as the chart dependency and as
  `casting.yaml`'s images, and nothing keeps the two in step. Drift
  shows as `casting.yaml`'s `signoz/signoz` tag differing from
  `signoz.image.tag` in `helm show values` for the pinned chart, which
  a bump of either should compare.
