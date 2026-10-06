# SigNoz dashboards

<!-- tessl-plugin: deployment -->

## Status

**Untested.** Nobody has followed these steps. The files, the operator's
handling of them and the rejections under Failures were checked on a
kind cluster on 2026-09-30, running SigNoz v0.144.0 and the SigNoz
Operator v0.0.2.

## Problem

You want to change a SigNoz dashboard or saved view, or add one, so
that every deployment of the chart has it.

## Solution

### Prerequisites

- A kind cluster running the chart with `values-local.yaml`, installed
  by `just kind-up`.

```bash
# the dashboard's file name, and its `name`, e.g.
export DASHBOARD=queenswood-api
```

Start at step 3 for a new dashboard. A saved view follows the same
steps with `savedviews.resources.signoz.io` in place of
`dashboards.resources.signoz.io` and its file under
`infra/helm/queenswood/files/signoz/views/`. The explorer shows no JSON
for a view, so write its file from the `SavedView` CRD's schema, with
`name` the file's name, `schemaVersion` `v2`, `source` `traces` and
the title the UI shows as `spec.displayName`.

### 1. Open SigNoz

```bash
just telemetry-ui
```

Sign in at `http://localhost:3301` as `dev@example.com` /
`Queenswood-dev-1`.

### 2. Stop the operator reverting the dashboard

```bash
kubectl -n queenswood patch dashboards.resources.signoz.io "$DASHBOARD" \
  --type merge -p '{"spec":{"suspend":true}}'
```

### 3. Edit it in SigNoz

Change the dashboard, or create one, then open **JSON** on it and copy
what it shows.

### 4. Save the file

Write it as `infra/helm/queenswood/files/signoz/dashboards/$DASHBOARD.json`
with two fields added at the top, `"name": "<the value of DASHBOARD>"`
and `"schemaVersion": "v6"`, beside the `tags` and `spec` it already
has.

### 5. Install it

```bash
just helm-install dev localhost:5001 "values-dev.yaml values-local.yaml" Always
```

### 6. Let the operator keep it again

```bash
kubectl -n queenswood patch dashboards.resources.signoz.io "$DASHBOARD" \
  --type merge -p '{"spec":{"suspend":false}}'
```

### 7. Check

```bash
kubectl -n queenswood get dashboards.resources.signoz.io "$DASHBOARD"
```

`READY` `True`, and `REASON` `Created`, `Updated` or `Synced`.

## Failures

**`READY` `False` with `REASON` `Rejected`.** SigNoz refused the body,
and the resource's `Synced` condition carries its message. Three it
gives that the CRD's schema does not reveal: `x (12) must be less than
grid width 12`, since the grid is twelve wide, `panel must have one
query, found 3`, since several series in a panel go in as one
`signoz/CompositeQuery`, and `reduceTo is required`, since a number
panel over a metric needs its aggregation reduced to one value, as
`"reduceTo": "sum"`. A rejection is terminal until the resource
changes, so fix the file and install it again.

**A saved view `Rejected` with `a lowercase RFC 1123 label`.** Its
`name` is a title rather than a slug. Put the title in
`spec.displayName`.

**Edits made in the SigNoz UI gone within minutes.** The operator puts a
dashboard back to its resource at every interval. Step 2 is what stops
it.

**An upgrade failing with a conflict on `.spec.interval`.** A field the
operator writes a default into was set in the chart as well. Leave it
out of the template.

**A legend with no names, over a panel that has data.** A numeric
attribute, such as `http.response.status_code`, grouped as a
`string`. Group it as a `number`.

**Every dashboard `Unauthorized`.** SigNoz refuses the operator's key,
which is what a SigNoz whose volume was lost does. The Job that issued
it issues another when it next runs, and it runs once per change to what
it runs, so delete it and install again:

```bash
kubectl -n queenswood delete job \
  -l app.kubernetes.io/component=signoz-operator-key
```

## Rules

**MUST:**

- Keep each dashboard as a file under
  `infra/helm/queenswood/files/signoz/dashboards/`, named for its
  `name`, holding `name`, `schemaVersion` `v6`, `tags` and `spec`.
- Keep each saved view as a file under
  `infra/helm/queenswood/files/signoz/views/`, named for its `name`,
  holding `name`, `schemaVersion` `v2`, `source` and `spec`, its title
  in `spec.displayName`.
- Suspend a dashboard's resource before editing it in the SigNoz UI, and
  lift the suspension once its file is installed.
- Put several series in one panel as a `signoz/CompositeQuery`, and lay
  panels on a grid twelve wide.
- Group a numeric attribute as a `number`.
- Give a number panel over a metric a `reduceTo` on its aggregation.
- Install with `just helm-install`, and open SigNoz with
  `just telemetry-ui`.

**MUST NOT:**

- Set `interval`, or any field the operator writes a default into, on a
  `Dashboard` the chart renders.
- Keep a dashboard only in SigNoz. Nothing declares it, and it goes
  with the cluster's volumes.

## Discussion

We declare SigNoz's dashboards and saved views in the chart, as the
JSON SigNoz's API takes, and let the SigNoz Operator create them in
each deployment's SigNoz and put them back when they drift.

**Why no host appears in a dashboard.** A dashboard queries span names
and attributes, and each cluster runs its own SigNoz, so one file serves
kind and every instance. The operator reaches SigNoz at its Service,
`http://queenswood-signoz:8080`, which is the same address everywhere.
The SigNoz `just telemetry-start` runs beside the monolith has no
operator, and so none of these dashboards.

**Why the UI's JSON needs two fields added.** The operator sends the
file to SigNoz's API as it is, and the API wants the dashboard's `name`
and `schemaVersion`, which the JSON editor leaves out.

**Why the operator has a key a Job issued.** The operator signs in with
a SigNoz service account's API key, and SigNoz issues one only to
somebody signed in. The Job signs in as the root user SigNoz provisions
at startup, creates the `queenswood-operator` account with the
`signoz-editor` role, and writes the key into
`queenswood-signoz-operator-key`. It is generated in the cluster and
kept nowhere else, for the reason
[external-secrets](external-secrets.md) gives.

**Why the operator is vendored.** Its chart ships the CRDs as templates,
and Helm checks the chart's `Dashboard` resources against the API server
before creating anything, so they were refused on a first install. The
queenswood chart carries it under `charts/signoz-operator` with the
CRDs in `crds/`, as it does the Keycloak and FoundationDB operators.

## References

- [ADR-0032](../../adr/0032-signoz-is-configured-through-its-operator.md)
  — why SigNoz's content is declared in the chart.
- [traceability](../../tdd/traceability.md) — the spans and attributes a
  dashboard can query, and those missing.
