# Freezing the plane's cluster

<!-- tessl-plugin: deployment -->

## Status

**Verified** on 2026-09-25, freezing one installation's plane with its
one instance `down`: the pool drained in about four minutes from the
resize starting.

## Problem

You want to stop paying for the nodes of the cluster a management plane
runs on — its one node pool, taken to zero — leaving the cluster, the
plane's composite, the folder, the projects, the identities and every
instance where they are, so that
[plane-thaw-cluster](plane-thaw-cluster.md) can bring the nodes back.

## Solution

### Prerequisites

- A plane that is up, with `MGMT_CTX` reaching it.
- The capability each step names. Ours is a Google group; yours may
  differ.
- Step 2 — the plane at a queenswood revision composing its records
  bucket.
- Step 3 — write access to the manifests repository, and a merge.
- Every instance in the state it should hold while the plane is frozen.
- Every project in the estate carrying `adopt` in the manifest that
  declares it:

  ```bash
  grep -rn 'projectId' "$QW_INSTALLATIONS_REPO/$QW_CODE" | grep -v adopt
  ```

  Every id that lists wants an `adopt` beside it, spelled
  `projects/<id>`, merged before step 3.

```bash
# the installation code, e.g. qw01
export QW_CODE=qw01
# the private manifests repository, wherever it is checked out
export QW_INSTALLATIONS_REPO=../installations
```

### 1. Check the plane is settled

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

```bash
just crossplane-unready
just argo-apps-status
```

A header line with nothing under it from the first, and every
Application `Synced` and `Healthy` from the second — except a `down`
instance's, which read `OutOfSync`, `Degraded` or `Progressing` because
its cluster has no nodes. Finish anything else outstanding before going
on.

### 2. Record what the plane holds

**As the installation's platform admin.** Ours is
`grp-gcp-<code>-platform-admin@` — join for this step, then leave.

```bash
just plane-record
```

It ends `>>> <count> managed resources recorded to
gs://bkt-<code>-c-mgmt-records-<suffix>/<stamp>`. Five files under that
prefix: every managed resource, every composite and the `Release`s as
JSON, and the slot and external-name listings.

```bash
just plane-records
```

The stamp just written is the last line.

### 3. Merge the freeze

**No cloud capability.** Write access to the manifests repository.

```bash
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-pr-plane-freeze
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-merge
```

The pull request sets `spec.cluster.nodeCount` to `0` in
`$QW_CODE/installation.yml`. Within a few minutes of the merge the
plane's Argo syncs it and its Crossplane resizes its own pool.

### 4. Watch it stop

**As the installation's platform viewer.**

```bash
gcloud container clusters list --project="$(just _mgmt-project)" \
  --filter="name=$QW_CODE-c-mgmt" --format='value(status,currentNodeCount)'
```

`RECONCILING` with the old count while the resize runs, then `RUNNING`
with the old count still, then `RUNNING` and an empty count once the
pool has drained. `kubectl --context "$QW_CODE-mgmt" get nodes` then
answers `No resources found`, and every pod on the plane is `Pending`.

## Failures

**The pool is still at two long after the merge.** The plane never
read the merge: the `installation` Application is not syncing, most
often because the credential for the private repository has no
version. `just argo-apps-status` shows it; see
[argocd-github](argocd-github.md).

**`plane-record` answers `The specified bucket does not exist`.** The
plane is at a queenswood revision older than its records bucket, or
the installation's `environment.yml` states no `region`, without which
the bucket is not composed. Neither is the bucket having been deleted.

## Rules

**MUST:**

- Record what the plane holds with `just plane-record` before freezing
  it, as the installation's platform admin, and read the stamp back
  with `just plane-records`. The record is the only copy off your
  machine of what a successor would have to adopt.
- Freeze by merging `nodeCount: 0` into the installation's manifest,
  raised with `just gh-pr-plane-freeze` in the manifests repository.
  The repository then says the plane is stopped.
- Check the plane with `just crossplane-unready` and `just
  argo-apps-status` before freezing it. Nothing finishes what is
  outstanding once it stops.
- Put every instance in the state it should hold while frozen, and
  merge `adopt` for every project in the estate, before freezing.

**MUST NOT:**

- Never delete the plane's cluster or its composite to save cost.
  Freezing keeps both, and deleting either is a rebuild rather than a
  thaw.

**MAY:**

- Leave a plane frozen for as long as nothing needs reconciling.
- Freeze with a `down` instance's Applications reading `OutOfSync`,
  `Degraded` or `Progressing`. Its cluster has no nodes, so nothing
  there is outstanding.

Commands: `just crossplane-unready`, `just argo-apps-status`, `just
plane-record`, `just plane-records`, `just gh-pr-plane-freeze`.

## Discussion

We freeze a plane by declaring its own node pool empty. The pool is the
one the plane's Crossplane and Argo run on, so the merge setting it to
zero is the last one the plane reconciles: Crossplane resizes the pool,
GKE drains it, and the controllers that did it go `Pending` with
everything else. GKE finishes the resize whatever happens to the
provider that asked for it. What is left is the cluster's control
plane, which carries a fee of its own, and nothing else changes — the
network, the identities, the bindings and every instance stay as they
were, and an instance that was `down` stays down.

**Why the merge rather than a resize.** A resize by hand stops the
plane as well, but leaves the repository saying it runs on two nodes,
which is the one place anybody would look. The merge is what
[ADR-0022](../../adr/0022-cloud-foundation-and-environment-lifecycle.md)
means by off being a declared state rather than an absence of one, and
it costs one ordering rule on the way back: the thaw is merged before
the nodes return, or the plane comes up reading zero and stops itself
again.

**What stops with it.** Everything a plane does. An instance's `state`,
a release pin, a new zone record — each is a merge nothing reads until
the thaw, and the workloads already on an instance's cluster run on
unreconciled. ADR-0022 names this as the cost of a plane at zero:
acceptable outside prod.

**Why the record goes to a bucket.** A frozen plane loses nothing: its
managed resources are in the cluster's own store, which the control
plane keeps. The record is for the cluster not coming back. A plane
holds nothing the cloud does not, except two things — what
late-initialisation wrote into a spec, and the values of the
`Release`s that installed Crossplane and Argo — and
[plane-rebuild-cluster](plane-rebuild-cluster.md) reads both off the
plane itself. The records bucket is composed in the recovery project,
the one kept for what an installation must have after losing everything
else, with `Delete` withheld, and every record goes under a prefix of
its own, so none is ever overwritten.

**Why as the platform identity.** No human capability writes to a
bucket: humans are read-only or break-glass. `platformAdmin` is
`serviceAccountTokenCreator` on the platform identity, which holds
`storage.admin` on the folder, so `plane-record` passes
`--impersonate-service-account` on each call and nothing outlives the
command.

## References

- [plane-thaw-cluster](plane-thaw-cluster.md) — bringing the nodes
  back
- [plane-rebuild-cluster](plane-rebuild-cluster.md) — replacing the
  plane's cluster, and what a successor adopts
- [plane-install](plane-install.md) — building a
  plane where none is running
- [ADR-0022](../../adr/0022-cloud-foundation-and-environment-lifecycle.md)
  — down as a declared state, and what a plane at zero costs
- [ADR-0023](../../adr/0023-installation-naming-and-access.md) — the
  capabilities each step names
