# 31. Traces go to SigNoz, in the cluster that produces them

<!-- tessl-plugin: design -->

## Status

**Accepted**

## Context

Every service exports OpenTelemetry traces over OTLP/HTTP to one
endpoint, and a correlation id and W3C trace context follow a request
across the HTTP edge and the message bus — see
[traceability](../tdd/traceability.md). The property wanted is that an
operator can find the trace of a slow or failing request after the
fact, whatever has restarted since, and read the logs and metrics of
the same moment beside it.

The chart ran an all-in-one Jaeger with in-memory storage, on kind and
on every instance. A restart of its pod lost every span, so the trace
of an incident was gone by the time somebody looked, and it held traces
and nothing else.

The shortlist:

- **Keep the all-in-one Jaeger.** Rejected: storage in memory is the
  fault, and it holds traces only.
- **Jaeger on a persistent store.** Rejected: a storage cluster run for
  traces alone, with logs and metrics still somewhere else.
- **Tempo, Loki and Prometheus behind Grafana.** Rejected: three stores
  and a dashboard to run and upgrade to answer one question.
- **A hosted service.** Rejected: spans carry customers' identifiers,
  which would leave the installation, and mono's OTel component sends
  no header an ingestion key could travel in.
- **SigNoz, self-hosted in the cluster.** One OTLP-native backend for
  traces, logs and metrics on ClickHouse, persistent, with alerting,
  and the services' export unchanged.

## Decision

Every deployment of the chart runs SigNoz in its own cluster, and every
service sends its traces there over OTLP/HTTP.

The parts:

- Take SigNoz as a dependency of the queenswood chart from
  `charts.signoz.io`, pinned and on by default, and point each
  service's `OTEL_ENDPOINT` at its collector unless `otel.endpoint`
  names another.
- Provision SigNoz's root user at startup, because its collector
  refuses OTLP until an organisation exists and that user creates one.
- Generate the root user's password in the cluster, by a Job, into
  `queenswood-signoz-root`, and keep it nowhere else. Type a pair only
  where one is meant to be typed: `values-local.yaml` and
  `infra/signoz/casting.yaml`.
- Turn SigNoz's stats reporter off wherever it runs.
- Run the monolith loop's SigNoz from `infra/signoz/casting.yaml`
  through the `foundryctl` that `justfiles/telemetry.just` pins, with
  the chart's images and its UI on 3301, since the monolith holds 8080.
- Export traces only. Logs and metrics over OTLP are a change to mono's
  telemetry component, made there.

## Consequences

Easier:

- A trace survives SigNoz restarting, and an incident's traces are
  there when somebody looks.
- Logs, metrics and alerts can join traces in the same tool without a
  second backend.
- No service changed: the endpoint is the only difference.

Harder:

- Every cluster runs ClickHouse, its operator, ZooKeeper, a collector
  and the SigNoz server, on volumes: gigabytes where Jaeger took a
  quarter of one.
- An instance's traces go with its cluster. Shelving it or rebuilding
  its cluster loses them.
- Reading SigNoz as its root user on an instance needs the cluster
  access that reads the Secret.
- SigNoz is pinned twice, as the chart dependency and as
  `casting.yaml`'s images, and nothing keeps the two in step. Drift
  shows as `casting.yaml`'s `signoz/signoz` tag differing from
  `signoz.image.tag` in `helm show values` for the pinned chart, which
  a bump of either should compare.
