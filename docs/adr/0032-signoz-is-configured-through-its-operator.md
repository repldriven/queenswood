# 32. SigNoz is configured through its operator

<!-- tessl-plugin: design -->

## Status

**Accepted**

## Context

Each cluster runs its own SigNoz —
[ADR-0031](0031-traces-go-to-signoz-in-the-cluster-that-produces-them.md).
What an operator reads in it, its dashboards first and its alerts later,
is kept in SigNoz's own database, which dies with the cluster's volumes.
The property wanted is that every deployment has the same dashboards
from the moment it runs, reviewed in a pull request, and that one edited
by hand in a deployment goes back to what was reviewed.

Without it, a dashboard is built by hand in each deployment, lost when
an instance is shelved or its cluster rebuilt, and different wherever
somebody changed one.

The shortlist:

- **A dashboard built in the UI and exported by hand.** Rejected:
  nothing restores it, and every deployment is built separately.
- **The Terraform provider.** Rejected: the platform declares its cloud
  in Crossplane — [ADR-0016](0016-crossplane-over-terraform.md) — and a
  Terraform state per cluster is a second declaration with a second
  reconciler.
- **A chart Job that posts each dashboard through the API.** Rejected:
  a reconcile loop of our own, which runs when the Job does and restores
  nothing edited between runs.
- **The SigNoz Operator.** Dashboards, alerts, users, roles, service
  accounts and SSO as Kubernetes resources, created through SigNoz's API
  and put back when they drift, applied by Argo with the rest of the
  chart.

## Decision

Declare SigNoz's content in the queenswood chart as the SigNoz
Operator's resources, and let the operator create and keep it in each
cluster's SigNoz.

The parts:

- Vendor the operator's chart under `charts/signoz-operator`, its CRDs
  in `crds/`, because upstream ships them as templates Helm would not
  install ahead of the chart's own resources.
- Give the operator a SigNoz service account, `queenswood-operator`,
  holding `signoz-editor`, whose API key a Job issues in the cluster
  into `queenswood-signoz-operator-key` by signing in as the root user,
  and keep the key nowhere else.
- Name SigNoz at its Service in one `ProviderConfig`, the same address
  in every cluster.
- Render a `Dashboard` per file under `files/signoz/dashboards/`, each
  the body SigNoz's API takes, and set no field the operator writes a
  default into.

## Consequences

Easier:

- Every deployment has the same dashboards from its first sync, and a
  rebuilt cluster has them again.
- A change to a dashboard is a reviewed file, and an edit made in one
  deployment is put back.
- Alerts, and sign-in through Keycloak, can follow as resources of the
  same operator.

Harder:

- The operator is `v1alpha1` at v0.0.2, so its API can change under the
  chart, and a CRD under `crds/` is not upgraded by Helm.
- Editing a dashboard means suspending its resource, editing it in the
  UI and copying its JSON back into the file.
- A dashboard names span attributes, and nothing checks that a service
  still emits them. Drift shows as a panel with no data where there had
  been some, which reading the dashboards after a change to mono's
  telemetry is what catches.
