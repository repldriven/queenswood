# Shelving an instance

<!-- tessl-plugin: deployment -->

## Status

**Untested.** Derived from the `XCluster` composition, the unit's
Applications and the chart; nobody has shelved an instance yet. The
first [instance-unshelve](instance-unshelve.md) after it is this
installation's first restore.

## Problem

You want an instance to stop costing a cluster — its GKE cluster and
node pool deleted, not only scaled to zero — keeping its project,
database, address, secrets and backups, so that
[instance-unshelve](instance-unshelve.md) can bring it back with its
data restored.

## Solution

### Prerequisites

- An instance that is `up`, on a plane that is not frozen. One that is
  `down` is brought up first, with `gh-pr-up` in the manifests
  repository: its operators have to run to let their resources go.
- The capability each step names. Ours is a Google group; yours may
  differ.
- Steps 3 and 5 — write access to the manifests repository, and a
  merge.

```bash
# the installation code, the instance's env and its label
export QW_CODE=qw01
export QW_ENV=n
export QW_LABEL=test
# the private manifests repository, wherever it is checked out
export QW_INSTALLATIONS_REPO=../installations
```

### 1. Stop writing to it

**No cloud capability.** These are the bank's own workloads.

Scale the five writers to zero as
[instance-rebuild-cluster](instance-rebuild-cluster.md) step 2 lists
them, or on an instance nobody uses, stop using it. Wait five minutes,
so the mutation log ships what was last written.

### 2. Record the restore point

**As the installation's platform admin.** Ours is
`grp-gcp-<code>-platform-admin@` — join for this step, then leave.

```bash
just queenswood-instance-record "$QW_ENV" "$QW_LABEL"
```

It ends with the generation and version recorded, and the record's
path: `records/<stamp>` in the instance's backups bucket.

```bash
export QW_STAMP=$(just queenswood-instance-records "$QW_ENV" "$QW_LABEL" | tail -1)
export QW_GENERATION=$(just queenswood-instance-records "$QW_ENV" "$QW_LABEL" "$QW_STAMP" | jq -r .generation)
export QW_VERSION=$(just queenswood-instance-records "$QW_ENV" "$QW_LABEL" "$QW_STAMP" | jq -r .version)
```

### 3. Withdraw the workloads

**No cloud capability.** Write access to the manifests repository.

```bash
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-pr-workloads-withdraw
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-merge
```

The pull request sets `spec.source.exclude` in
`$QW_CODE/$QW_LABEL.unit.yml` to the unit's three Applications. Once
the plane syncs it, `just argo-apps-status` shows the unit's own
Application `OutOfSync` and the three unchanged.

### 4. Delete the workloads

**As the installation's cluster admin.** Ours is
`grp-gcp-<code>-cluster-admin@` — join for this step, then leave.

> [!WARNING]
> This deletes FoundationDB's volumes. From here the data exists only in
> the backup recorded in step 2.

```bash
kubectl --context "$QW_CODE-mgmt" -n argocd delete application \
  "$QW_CODE-$QW_ENV-$QW_LABEL-queenswood" \
  "$QW_CODE-$QW_ENV-$QW_LABEL-config" \
  "$QW_CODE-$QW_ENV-$QW_LABEL-external-secrets"
```

It returns once every resource the three installed is gone. Then:

```bash
PROJECT=$(just _instance-project "$QW_ENV" "$QW_LABEL")
gcloud compute disks list --project="$PROJECT" --filter='name~^pvc-' --format='value(name)'
gcloud compute forwarding-rules list --project="$PROJECT" --format='value(name)'
```

No output from either.

### 5. Shelve it

**No cloud capability.** Write access to the manifests repository.

```bash
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-pr-shelve "$QW_GENERATION" "$QW_VERSION"
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-merge
```

The pull request sets `state: "down"` and `cluster.shelved: true` in
`$QW_CODE/units/$QW_LABEL/instance.yml`, and `fdb.restore` to the point
recorded in step 2 in `$QW_CODE/units/$QW_LABEL/values.yml`. It refuses
while step 3 is not on `main`.

### 6. Watch it go

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

```bash
PROJECT=$(just _instance-project "$QW_ENV" "$QW_LABEL")
gcloud container clusters list --project="$PROJECT" --format='value(name,status)'
```

`STOPPING` within a few minutes of the merge, then no output.

## Failures

**Step 4 does not return, and an Application stays in deletion.** A
resource it installed carries a finalizer, and the operator that clears
it was deleted first — both ship in the `queenswood` Application. Find
the resource on the instance's cluster and remove its finalizer: the
cluster is going, and nothing the finalizer guarded outlives it.

**A disk is still listed after step 4.** A PersistentVolumeClaim
outlived its owner, or its volume's reclaim policy is `Retain`. Delete
the claim, or the disk, before step 5: a disk left when the cluster
goes is billed and nothing adopts it.

## Rules

**MUST:**

- Record the restore point with `just queenswood-instance-record`
  before withdrawing the workloads, and read it back with `just
  queenswood-instance-records`.
- Shelve only an instance that is `up`, on a plane that is not frozen,
  bringing a `down` one up with `just gh-pr-up` first.
- Withdraw the workloads with `just gh-pr-workloads-withdraw` and delete
  their Applications while the instance is `up`, since an operator's
  finalizer needs its pod to run.
- Check no `pvc-` disk and no forwarding rule remains in the instance's
  project before shelving it.
- Shelve with `just gh-pr-shelve`, which carries the recorded point into
  `fdb.restore` in the same merge.

**MUST NOT:**

- Never shelve an instance whose data matters before an unshelve has
  been proven on one whose data does not.
- Never delete the instance's composite to stop paying for its cluster.

**MAY:**

- Leave an instance shelved for as long as nobody needs it.

Commands: `just queenswood-instance-record`, `just
queenswood-instance-records`, `just gh-pr-workloads-withdraw`, `just
gh-pr-shelve`, `just argo-apps-status`.

## Discussion

We shelve an instance by deleting its workloads and then declaring its
cluster absent. The instance's composite stops composing the `Cluster`
and `NodePool`, and Crossplane deletes what it no longer composes. What
stays is everything outside the cluster: the project, the network, the
identities, the Cloud SQL database — stopped, since a shelved instance
is `down` — the address, the certificates and records, the Secret
Manager entries and, in the recovery project, the backups and their
key. The plane's own cluster is the only one left on the billing
account, which the free tier covers.

**Why the workloads come off first.** A cluster deleted with workloads
on it leaves behind what those workloads caused GCP to create: a
persistent disk for every claim and a load balancer for the Gateway.
Both are billed and nothing adopts them. Deleting the Applications with
their finalizer lets the cluster's own controllers delete both while
they can. The unit's Application has `prune: false` and no finalizer,
so withdrawing the files alone detaches them without deleting anything,
and the deletion is a separate act by a person — the one that destroys
data.

**Why the record is in a bucket and the merge.** The point is read off
the bucket's mutation log and written back beside it, under `records/`,
in the recovery project the instance's teardown never touches. The
merge carries the same point into `fdb.restore`, so the instance's
values already say where it comes back from. A live cluster ignores it,
since the restore Job leaves a populated destination alone.

## References

- [instance-unshelve](instance-unshelve.md) — bringing a shelved
  instance back
- [ADR-0029](../../adr/0029-a-shelved-instance-keeps-everything-but-its-cluster.md)
  — what shelving keeps, and why it is not a value of `state`
- [instance-rebuild-cluster](instance-rebuild-cluster.md) — stopping
  writes, and a rebuild without the gap
- [fdb-recovery](fdb-recovery.md) — generations, versions and what a
  restore proves
- [plane-freeze-cluster](plane-freeze-cluster.md) — the plane's
  equivalent, which keeps its cluster
