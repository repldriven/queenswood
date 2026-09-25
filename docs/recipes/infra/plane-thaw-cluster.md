# Thawing the plane's cluster

<!-- tessl-plugin: deployment -->

## Status

**Untested.** Derived from
[plane-freeze-cluster](plane-freeze-cluster.md), which it reverses, and
from [plane-rebuild-cluster](plane-rebuild-cluster.md)
step 7, which checks a plane holds what it held.

## Problem

You want the nodes back on the cluster a frozen management plane runs
on, so the plane reconciles the installation from the manifests
repository again.

## Solution

### Prerequisites

- A plane frozen by [plane-freeze-cluster](plane-freeze-cluster.md),
  its cluster still there.
- The capability each step names. Ours is a Google group; yours may
  differ.
- Step 1 — write access to the manifests repository, and a merge.

Where the cluster is gone, this is not the procedure: raise a boot
plane and install, which adopts what survived — see
[plane-install](plane-install.md) — and diff what
it adopted against the last record, as step 4 does.

```bash
# the installation code, e.g. qw01
export QW_CODE=qw01
# the private manifests repository, wherever it is checked out
export QW_INSTALLATIONS_REPO=../installations
export WORK=$(mktemp -d)
```

### 1. Merge the thaw

**No cloud capability.** Write access to the manifests repository.

```bash
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-pr-plane-thaw
just -f "$QW_INSTALLATIONS_REPO/Justfile" gh-merge
```

The pull request sets `spec.cluster.nodeCount` back to `2` in
`$QW_CODE/installation.yml`. Nothing reads it yet, and nothing changes.

### 2. Bring the nodes back

**As the installation's cluster admin.** Ours is
`grp-gcp-<code>-cluster-admin@` — join for this step, then leave.

```bash
export PROJECT=$(just _mgmt-project)
export ZONE=$(gcloud container clusters list --project="$PROJECT" \
                --filter="name=$QW_CODE-c-mgmt" --format='value(location)')
gcloud container clusters resize "$QW_CODE-c-mgmt" \
  --node-pool="np-$QW_CODE-c-mgmt-primary" --num-nodes=2 \
  --zone="$ZONE" --project="$PROJECT"
```

The count is the one step 1 merged. A few minutes for the nodes, and
the resize reports done once both are registered.

### 3. Let it settle

**As the installation's platform viewer.** Ours is
`grp-gcp-<code>-platform-viewer@`, populated rather than joined.

```bash
just plane-ctx
kubectl --context "$QW_CODE-mgmt" get nodes
just argo-apps-status
just crossplane-unready
```

Two nodes `Ready`, every Application `Synced` and `Healthy`, and a
header line with nothing under it. Crossplane and Argo start on the
nodes as they arrive, and the providers take several minutes to pull
their images before anything reports.

### 4. Check it holds what it held

**As the installation's platform admin.** Ours is
`grp-gcp-<code>-platform-admin@` — join for this step, then leave.

```bash
export STAMP=$(just plane-records | tail -1)
just plane-records "$STAMP" "$WORK"
just crossplane-slots > "$WORK/slots-after.txt"
just crossplane-external-names > "$WORK/names-after.txt"
diff "$WORK/slots.txt" "$WORK/slots-after.txt"
diff "$WORK/names.txt" "$WORK/names-after.txt"
```

No output from either diff. A line in either is a managed resource the
plane holds now and did not at the freeze, or the reverse; read it
against the merges made while the plane was frozen, which it has just
applied.

## Failures

**The nodes come up and drain again within minutes.** The thaw was not
merged before the resize, so the plane read `nodeCount: 0` from the
manifest as it started and stopped itself. Merge it and resize again.

**`just plane-ctx` reports no cluster.** The cluster was deleted while
the plane was frozen. See the paragraph under Prerequisites.

## Rules

**MUST:**

- Merge the thaw with `just gh-pr-plane-thaw` before resizing the pool.
  The plane reads its manifest as it starts, and one still reading zero
  stops itself again.
- Resize the pool to the count the thaw merged.
- Diff `just crossplane-slots` and `just crossplane-external-names`
  against the last record, fetched with `just plane-records`, before
  trusting the plane.

**MUST NOT:**

- Never thaw a plane whose cluster is gone. That is
  [plane-install](plane-install.md), which adopts
  what survived.

Commands: `just gh-pr-plane-thaw`, `just plane-ctx`, `just
argo-apps-status`, `just crossplane-unready`, `just plane-records`, `just
crossplane-slots`, `just crossplane-external-names`.

## Discussion

We thaw a plane by declaring its nodes back and then putting them
there. The declaration has to come first: the plane stopped itself by
reconciling its own pool to zero, and it reconciles the same field the
moment it runs again, so a pool resized under a manifest still saying
zero is emptied by the controller it just started. Once the merge has
landed, the resize and the manifest agree and the plane has nothing to
correct.

**What happens as it starts.** Nothing is adopted: the managed
resources never left the cluster's store, so each provider resumes
observing what it was already managing. What it has missed is every
merge made while it was frozen, and it applies those in the order Argo
reads them — which is why the diff in step 4 is read against them
rather than expected to be empty whatever happened.

**Why the resize is by hand.** Nothing else can do it. The only thing
that reconciles the plane's pool is the plane.

## References

- [plane-freeze-cluster](plane-freeze-cluster.md) — freezing the
  plane's cluster, and the record
  this diffs against
- [plane-rebuild-cluster](plane-rebuild-cluster.md) — checking a plane
  holds what it held
- [plane-install](plane-install.md) — building a
  plane where the cluster is gone
