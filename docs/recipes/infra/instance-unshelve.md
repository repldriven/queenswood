# Unshelving an instance

<!-- tessl-plugin: deployment -->

## Status

**Untested.** Derived from the `XCluster` composition, the chart's
restore path and [instance-rebuild-cluster](instance-rebuild-cluster.md),
itself untested. The first run is this installation's first restore,
and its first measurement of how long one takes.

## Problem

You want a shelved instance back: its cluster built again under the
same name, its workloads installed, and FoundationDB restored from the
point recorded when it was shelved.

## Solution

### Prerequisites

- An instance shelved with [instance-shelve](instance-shelve.md), on a
  plane that is not frozen.
- The capability each step names. Ours is a Google group; yours may
  differ.
- Steps 2 and 4 — write access to the manifests repository, and a
  merge.

```bash
# the installation code, the instance's env and its label
export QW_CODE=qw01
export QW_ENV=n
export QW_LABEL=test
# the private manifests repository, wherever it is checked out
export QW_INSTALLATIONS_REPO=../installations
```

### 1. Read the restore point back

**As the installation's platform admin.** Ours is
`grp-gcp-<code>-platform-admin@` — join for this step, then leave.

```bash
export QW_STAMP=$(just queenswood-instance-records "$QW_ENV" "$QW_LABEL" | tail -1)
just queenswood-instance-records "$QW_ENV" "$QW_LABEL" "$QW_STAMP"
git -C "$QW_INSTALLATIONS_REPO" pull --quiet
sed -n '/^  restore:/,/^  [a-z]/p' "$QW_INSTALLATIONS_REPO/$QW_CODE/units/$QW_LABEL/values.yml"
```

The `generation` and `version` in the record are the `backupName` and
`version` under `restore:`, which says `enabled: true`.

### 2. Unshelve it

**No cloud capability.** Write access to the manifests repository.

```bash
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-pr-unshelve "fdb/continuous/$(date -u +%F)"
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-merge
```

The pull request sets `state: "up"` and `cluster.shelved: false` in
`$QW_CODE/units/$QW_LABEL/instance.yml`, and `fdb.backup.backupName` to
the new generation in `$QW_CODE/units/$QW_LABEL/values.yml`. It refuses
a generation the restore reads from.

### 3. Wait for the cluster

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

```bash
PROJECT=$(just _instance-project "$QW_ENV" "$QW_LABEL")
gcloud container clusters list --project="$PROJECT" --format='value(name,status,currentNodeCount)'
just crossplane-unready
```

`PROVISIONING`, then `RUNNING` with the pool's count; then nothing of
the instance's from the second.

### 4. Return the workloads

**No cloud capability.** Write access to the manifests repository.

```bash
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-pr-workloads-return
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-merge
```

The pull request empties `spec.source.exclude` in
`$QW_CODE/$QW_LABEL.unit.yml`. The plane installs the three
Applications again, and the chart restores before anything starts: the
restore Job renders because `fdb.restore.enabled` is true, finds the
destination empty and restores; the migrator waits on it, bootstrap on
the migrator, and every service on bootstrap.

```bash
just argo-apps-status
```

The unit's four Applications `Synced` and `Healthy`.

### 5. Verify the restore, not the Job

**As the installation's cluster admin.** Ours is
`grp-gcp-<code>-cluster-admin@` — join for this step, then leave.

```bash
just queenswood-instance-ctx "$QW_ENV" "$QW_LABEL"
kubectl --context "$QW_CODE-$QW_ENV-$QW_LABEL" -n queenswood exec -it \
  deploy/queenswood-fdb-backup-agents -- \
  fdbrestore status --dest-cluster-file /etc/fdb/fdb.cluster
```

`State: completed` with `LastError: None`. Then sign in through the
console and confirm a party resolves.

## Failures

**The Applications report the cluster unreachable after step 4.** Argo's
registration still carries no address, because the composite had not
read the new cluster's back when the Applications arrived. It corrects
itself on the next reconcile; nothing needs doing.

## Rules

**MUST:**

- Unshelve only on a plane that is not frozen.
- Check the restore point in the instance's values against the last
  record from `just queenswood-instance-records` before unshelving.
- Unshelve with `just gh-pr-unshelve`, naming a new generation for
  `fdb.backup.backupName`, never the one the restore reads from.
- Return the workloads with `just gh-pr-workloads-return` only once the
  cluster is `RUNNING`.
- Verify with `fdbrestore status` and a sign-in, never with the restore
  Job's exit status.

**MUST NOT:**

- Never write a secret again to unshelve. The entries survive a shelve,
  and a new version of the FDB backup key strands every backup written
  under the old one.

**MAY:**

- Leave `fdb.restore` set afterwards: it is a target rather than a
  mode, and the next shelve replaces it.

Commands: `just queenswood-instance-records`, `just gh-pr-unshelve`,
`just crossplane-unready`, `just gh-pr-workloads-return`, `just
argo-apps-status`, `just queenswood-instance-ctx`.

## Discussion

We unshelve an instance by declaring its cluster present and then
returning its workloads to it. The composite composes the `Cluster` and
`NodePool` from scratch under the names they had, since a GKE cluster's
name is unique only within its project and zone and is free again once
the deletion completes. Both managed resources are new too, so no field
late-initialised from the old cluster is sent to GCP as a create
parameter, which is what [instance-rebuild-cluster](instance-rebuild-cluster.md)
step 6b works around. What is new is the cluster's endpoint and
certificate authority, which the composite reads back into Argo's
registration, and FoundationDB's version numbers, which restart near
zero — hence the new generation.

**What nothing has to rewrite.** Every secret the workloads read comes
from Secret Manager in the instance's project, through the
`ExternalSecret`s the config Application installs, as an identity bound
by Workload Identity. The pool that identity belongs to is the
project's rather than the cluster's, so the bindings made when the
instance was deployed answer the new cluster unchanged. Keycloak's
bootstrap admin is the one its database was created with, and the
database never went anywhere, so the realm comes back with the users it
had and FoundationDB's records still name them.

**Why two merges.** The workloads' Applications name the cluster by the
registration the composite writes, and that has no address until the
cluster reports one. Returning them once the cluster is `RUNNING` keeps
the restore's first attempt from meeting a cluster that is not there.

## References

- [instance-shelve](instance-shelve.md) — shelving, and the record this
  reads
- [ADR-0029](../../adr/0029-a-shelved-instance-keeps-everything-but-its-cluster.md)
  — what shelving keeps
- [instance-rebuild-cluster](instance-rebuild-cluster.md) — the same
  restore, without the gap
- [fdb-recovery](fdb-recovery.md) — generations, versions and what a
  restore proves
- [external-secrets](external-secrets.md) — why a rebuilt cluster reads
  the same entries
