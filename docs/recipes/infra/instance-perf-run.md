# Load testing an instance

<!-- tessl-plugin: deployment -->

## Status

**Untested.** Written before any run on an instance. The run is
`perf-run` as it runs on kind, described in
[performance-testing](../../tdd/performance-testing.md); the operator
client is created and deleted through Keycloak's admin API as the
bootstrap Job reaches it, and those calls were exercised against a local
Keycloak 26.7 on 2026-10-11, its token carrying the `admin` realm role.

## Problem

You want to run the load tests under `perf/` against a deployed
instance, as they run on kind, without leaving anything in the instance
that could start one when nobody is running a test.

## Solution

Start at step 1 for a first run, at step 3 for a later one from a shell
that already has the context, and at step 5 when a run was killed before
it finished.

### Prerequisites

- Steps 2–5 — an instance that is `up`, running the simulators, on a
  release whose platform policy declares the `perf` tier, offering
  Modulr and Zyphe.
- Steps 2–5 — the installation's cluster admin capability, to read the
  Keycloak bootstrap admin's Secret and run the Job. Ours is
  `grp-gcp-<code>-cluster-admin@` — join for the run, then leave.
- Steps 2–4 — for a run whose figures are published, a load pool on
  the instance: `cluster.load` in its `instance.yml`, naming a machine
  type.
- Step 3 — local port 18090 free, for the port-forward to Keycloak.

```bash
# the installation code, the instance's env and its label
export QW_CODE=qw01
export QW_ENV=n
export QW_LABEL=test
```

### 1. Add the instance's context

```bash
just queenswood-instance-ctx "$QW_ENV" "$QW_LABEL"
```

`kubectl config get-contexts` lists `$QW_CODE-$QW_ENV-$QW_LABEL`.

### 2. Check the instance can take a run

```bash
kubectl --context "$QW_CODE-$QW_ENV-$QW_LABEL" -n queenswood get deploy \
  queenswood-api-service queenswood-external-simulators-service
```

Both read `READY` with every replica available.

```bash
kubectl --context "$QW_CODE-$QW_ENV-$QW_LABEL" -n queenswood get job queenswood-perf
```

It reports `NotFound`, or a Job that has completed.

```bash
kubectl --context "$QW_CODE-$QW_ENV-$QW_LABEL" get nodes \
  -l platform.repldriven.com/pool=load
```

One node, `Ready`, on an instance that declares a load pool, and `No
resources found` on one that does not.

### 3. Run a smoke

> [!WARNING]
> Each run creates a bank on the instance, named `Perf` and the time it
> started, with its parties, accounts and payments. The platform deletes
> no bank, so it stays.

```bash
just perf-run-instance "$QW_CODE-$QW_ENV-$QW_LABEL" internal smoke
```

It opens with `created the queenswood-perf client, holding admin, and
queenswood-perf-operator`, prints the run's `run` and `headline`,
names the summary under `target/perf/`, and closes with `deleted the
queenswood-perf client`.

### 4. Run the profile you came for

```bash
just perf-run-instance "$QW_CODE-$QW_ENV-$QW_LABEL" internal challenger
```

The output has the same shape as the smoke's. The other scenarios and
the overrides are `perf-run`'s, listed by `just --list`.

### 5. Clean up after a run that was killed

```bash
just perf-clean-instance "$QW_CODE-$QW_ENV-$QW_LABEL"
```

It reports the client deleted, where one was left, and the Secret, Job
and ConfigMap deleted or not found.

## Failures

- **`Keycloak gave the bootstrap admin no token`.** The Secret's
  password is not the one Keycloak's database holds, as after a cluster
  rebuild that regenerated it rather than materialising it from Secret
  Manager. `keycloak.bootstrapAdmin.secretName` in the chart's
  [values.yaml](/infra/helm/queenswood/values.yaml) says which it is.
- **Setup fails at `creating the bank`.** The instance does not declare
  the `perf` tier, or does not offer Modulr or Zyphe. The body k6 prints
  names which.
- **Setup fails with `no Zyphe run` or at `deciding a verification`.**
  k6 decides each party at the IDV simulator, so an instance whose IDV
  provider is a vendor cannot set a run up.

## Rules

**MUST:**

- Run a load test on an instance with `just perf-run-instance`, which
  creates its operator client before the run and deletes it after.
- Run `just perf-clean-instance` after any run that did not end itself.
- Run a smoke before a longer profile on the same instance.
- Declare a load pool, `cluster.load` in the instance's manifest, before
  a run whose figures are published.

**MUST NOT:**

- Never give a deployed realm a standing operator client for load
  tests, in its values or by hand.
- Never publish a run's figures as the instance's capacity while the Job
  shares the services' nodes.

**MAY:**

- Run on an instance with no load pool, where the Job shares the
  services' nodes.
- Disable the site's Register links with `just site-publish down` for
  the run's length, and enable them again with `just site-publish up`.

Commands: `just queenswood-instance-ctx`, `just perf-run-instance`,
`just perf-clean-instance`, `just site-publish`.

## Discussion

We run the load test on an instance the way it runs on kind: k6 as a Job
inside the instance's cluster, reaching the API and the simulators by
their Services, with nothing through the Gateway. What kind has and an
instance does not is an operator the Job can sign in as, which the run
needs only to create its bank on the `perf` tier. We create that client
for the run and delete it afterwards.

**Why the client is created and deleted.** The run's setup creates a
fresh bank on the `perf` tier, and only an operator may name a tier. On
kind the dev realm carries `queenswood-perf`, and the chart refuses that
client under any other Keycloak mode, so no deployed realm can carry it.
The platform's own operator client signs with a key only the services
mount. So `perf-run-instance` signs in to Keycloak's admin API as the
bootstrap admin, the identity the bootstrap Job already uses, creates
`queenswood-perf` holding `admin` in the `queenswood` realm, writes its
secret to `queenswood-perf-operator` for the Job, and deletes both when
the run ends, however it ends. Between runs, nothing in the realm or the
product can start a load test.

**Why a fresh bank each run.** A run sets up fifty parties, each with a
verification session, and the platform allows a bank a hundred sessions
a day, so one bank made ahead of time would allow two runs a day. A
fresh bank also starts every daily count and limit at zero.

**How it reaches the instance.** The admin calls go through a
port-forward, since they are a handful of requests. The run is
`perf-run` itself, given a kubeconfig holding only the instance's
context, so its `kubectl` lands on the instance and the shell's current
context is left alone.

**Where the Job runs.** An instance whose manifest declares
`cluster.load` composes a second node pool,
`np-<code>-<env>-<label>-load`, whose nodes carry the label
`platform.repldriven.com/pool=load` and a `NoSchedule` taint of the same
key and value. The taint keeps the bank's workloads off the pool with no
chart naming it, and the Job tolerates it. `perf-run-instance` looks for
a node carrying the label and gives the Job a selector for it, so k6
takes nothing from what it measures. Where it finds none it says so, and
the Job is scheduled beside the services.

**What a run shares.** The instance's sandbox users share its FDB and
Kafka for the run's length. On an instance with no load pool k6 shares
the services' nodes as well, and that run's figures say how the instance
behaved under load while doing its usual work, not what it can carry.

## References

- [performance-testing](../../tdd/performance-testing.md) — the
  scenarios, profiles and what a run reports
- [instance-deploy](instance-deploy.md) — the instance a run goes
  against
- [external-secrets](external-secrets.md) — where the bootstrap admin's
  Secret comes from
- [web-install](web-install.md) — the site whose Register links
  `site-publish` disables
